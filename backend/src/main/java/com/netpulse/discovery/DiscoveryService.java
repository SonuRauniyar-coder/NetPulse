package com.netpulse.discovery;

import com.netpulse.audit.AuditService;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.error.ApiException;
import com.netpulse.common.process.OsFamily;
import com.netpulse.common.process.ProcessRunner;
import com.netpulse.common.web.CurrentUser;
import com.netpulse.common.web.ToolRateLimiter;
import com.netpulse.device.DnsRecordType;
import com.netpulse.monitoring.dns.DnsChecker;
import com.netpulse.monitoring.dns.DnsOutcome;
import com.netpulse.monitoring.ping.PingOutcome;
import com.netpulse.monitoring.ping.PingService;
import com.netpulse.settings.SettingsService;
import com.netpulse.subnet.SubnetCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LAN discovery: ping every host address in a range with bounded concurrency, read the OS neighbor table for MACs,
 * then add vendor and reverse DNS. Hosts that block ICMP can still appear, because ARP has to work for them to
 * use the LAN at all; those are flagged as "ARP only".
 */
@Service
public class DiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    public record ScanDto(UUID id, String cidr, String status, String requestedBy, int total, int scanned, int found,
                          String error, Instant startedAt, Instant finishedAt) { }

    public record HostDto(String ip, String mac, String vendor, String hostname, Double rttMs, boolean icmpResponded, boolean monitored) { }

    public record ScanDetail(ScanDto scan, List<HostDto> hosts) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PingService ping;
    private final DnsChecker dns;
    private final ProcessRunner processes;
    private final OuiDatabase oui;
    private final SettingsService settings;
    private final AuditService audit;
    private final ToolRateLimiter rateLimiter;
    private final ExecutorService executor;
    private final NetPulseProperties props;
    private final Clock clock;
    private final OsFamily os = OsFamily.detect();
    private final Semaphore scanSlots;

    public DiscoveryService(JdbcTemplate jdbc, TransactionTemplate tx, PingService ping, DnsChecker dns, ProcessRunner processes,
                            OuiDatabase oui, SettingsService settings, AuditService audit, ToolRateLimiter rateLimiter,
                            ExecutorService monitoringExecutor, NetPulseProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ping = ping;
        this.dns = dns;
        this.processes = processes;
        this.oui = oui;
        this.settings = settings;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.executor = monitoringExecutor;
        this.props = props;
        this.clock = clock;
        this.scanSlots = new Semaphore(props.discovery().maxConcurrentScans());
    }

    /** A restart kills any running sweep. Without this its row would claim to be RUNNING forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        jdbc.update("update discovery_scan set status = 'INTERRUPTED', finished_at = ? where status = 'RUNNING'", Timestamp.from(clock.instant()));
    }

    public ScanDto start(String rawCidr) {
        if (props.demoMode()) throw ApiException.conflict("Scans don't run in demo mode.");
        SubnetCalculator.Cidr cidr = SubnetCalculator.parse(rawCidr);
        ScanAuthorizer.check(cidr, settings.current().scanCidrs(), props.discovery().minPrefix());
        String user = CurrentUser.name();
        rateLimiter.acquire("discovery", user, props.discovery().perUserPerMinute());
        if (!scanSlots.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "A scan is already running. Wait for it to finish.");
        }
        try {
            List<Long> addresses = SubnetCalculator.hostAddresses(cidr);
            UUID id = UUID.randomUUID();
            Instant now = clock.instant();
            // The audit row commits with the scan row: a scan can never start unrecorded.
            tx.executeWithoutResult(s -> {
                jdbc.update("insert into discovery_scan (id, cidr, status, requested_by, total, started_at) values (?, ?, 'RUNNING', ?, ?, ?)",
                        id, cidr.toString(), user, addresses.size(), Timestamp.from(now));
                audit.record("DISCOVERY_SCAN", "discovery", id, "Scan of " + cidr + " (" + addresses.size() + " addresses)");
            });
            executor.submit(() -> runScan(id, addresses));
            return get(id).scan();
        } catch (RuntimeException e) {
            scanSlots.release();
            throw e;
        }
    }

    private void runScan(UUID id, List<Long> addresses) {
        try {
            Sweep sweep = sweep(id, addresses);
            NeighborRead neighbors = readNeighbors(addresses);
            List<HostDto> hosts = enrich(sweep.alive(), neighbors.macs());
            store(id, hosts, sweep, neighbors.error());
            log.info("Scan {} finished: {} hosts of {} addresses", id, hosts.size(), addresses.size());
        } catch (RuntimeException e) {
            log.error("Scan {} failed", id, e);
            jdbc.update("update discovery_scan set status = 'FAILED', error = ?, finished_at = ? where id = ?",
                    "Scan failed unexpectedly", Timestamp.from(clock.instant()), id);
        } finally {
            scanSlots.release();
        }
    }

    private record Sweep(Map<String, Double> alive, int scanned, boolean complete) { }
    private record NeighborRead(Map<String, String> macs, String error) { }

    private Sweep sweep(UUID id, List<Long> addresses) {
        var cfg = props.discovery();
        long deadline = System.nanoTime() + cfg.maxDuration().toNanos();
        Semaphore permits = new Semaphore(cfg.maxConcurrentProbes());
        Map<String, Double> alive = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>(addresses.size());
        for (long a : addresses) {
            tasks.add(executor.submit(() -> {
                try {
                    permits.acquire();
                    try {
                        String ip = SubnetCalculator.format(a);
                        PingOutcome o = ping.pingOnce(InetAddress.getByName(ip), cfg.probeTimeout());
                        if (o.success()) alive.put(ip, o.rttAvg());
                    } finally {
                        permits.release();
                    }
                    done.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (UnknownHostException e) {
                    throw new IllegalStateException(e);   // cannot happen: the text is a dotted-quad literal
                }
            }));
        }

        boolean complete = true;
        long lastWrite = 0;
        for (Future<?> f : tasks) {
            try {
                f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                complete = false;
                break;
            } catch (ExecutionException e) {
                log.debug("Probe failed: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                complete = false;
                break;
            }
            if (System.nanoTime() - lastWrite > 500_000_000L) {   // keeps the progress bar moving without a write per host
                jdbc.update("update discovery_scan set scanned = ?, found = ? where id = ?", done.get(), alive.size(), id);
                lastWrite = System.nanoTime();
            }
        }
        if (!complete) tasks.forEach(t -> t.cancel(true));
        return new Sweep(alive, done.get(), complete);
    }

    private NeighborRead readNeighbors(List<Long> addresses) {
        Set<String> inScope = new HashSet<>();
        addresses.forEach(a -> inScope.add(SubnetCalculator.format(a)));
        try {
            Map<String, String> all = NeighborTableParser.parse(os,
                    processes.run(NeighborTableParser.command(os), java.time.Duration.ofSeconds(5)).output());
            all.keySet().retainAll(inScope);
            return new NeighborRead(all, null);
        } catch (RuntimeException e) {
            log.warn("Could not read the neighbor table: {}", e.getMessage());
            return new NeighborRead(Map.of(), "MAC addresses unavailable: " + e.getMessage());
        }
    }

    private List<HostDto> enrich(Map<String, Double> alive, Map<String, String> macs) {
        Set<String> ips = new TreeSet<>(Comparator.comparingLong(SubnetCalculator::toLong));
        ips.addAll(alive.keySet());
        ips.addAll(macs.keySet());
        Semaphore permits = new Semaphore(props.discovery().maxConcurrentProbes());
        List<Future<HostDto>> lookups = new ArrayList<>();
        for (String ip : ips) {
            lookups.add(executor.submit(() -> {
                permits.acquire();
                try {
                    String mac = macs.get(ip);
                    return new HostDto(ip, mac, oui.lookup(mac), reverseDns(ip), alive.get(ip), alive.containsKey(ip), false);
                } finally {
                    permits.release();
                }
            }));
        }
        List<HostDto> out = new ArrayList<>(lookups.size());
        for (Future<HostDto> f : lookups) {
            try {
                out.add(f.get());
            } catch (ExecutionException e) {
                log.debug("Enrichment failed: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out;
    }

    private String reverseDns(String ip) throws UnknownHostException {
        DnsOutcome r = dns.check(DnsRecordType.PTR, null, null, InetAddress.getByName(ip));
        if (!r.success() || r.answers().isEmpty()) return null;
        String name = r.answers().getFirst();
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private void store(UUID id, List<HostDto> hosts, Sweep sweep, String note) {
        tx.executeWithoutResult(s -> {
            for (HostDto h : hosts) {
                jdbc.update("insert into discovery_host (scan_id, ip, mac, vendor, hostname, rtt_ms, icmp_responded) values (?, ?, ?, ?, ?, ?, ?)",
                        id, h.ip(), h.mac(), truncate(h.vendor(), 160), truncate(h.hostname(), 253), h.rttMs(), h.icmpResponded());
            }
            String error = sweep.complete() ? note : "Stopped at the maximum scan duration; results are partial.";
            jdbc.update("update discovery_scan set status = ?, scanned = ?, found = ?, error = ?, finished_at = ? where id = ?",
                    sweep.complete() ? "COMPLETED" : "PARTIAL", sweep.scanned(), hosts.size(), truncate(error, 255), Timestamp.from(clock.instant()), id);
        });
    }

    public ScanDetail get(UUID id) {
        ScanDto scan = jdbc.query(SCAN_SELECT + " where id = ?", (rs, n) -> toScan(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Scan"));
        List<HostDto> hosts = jdbc.query("""
                select h.ip, h.mac, h.vendor, h.hostname, h.rtt_ms, h.icmp_responded,
                       exists (select 1 from device d where d.host = h.ip or d.resolved_ip = h.ip)
                from discovery_host h where h.scan_id = ? order by h.id""",
                (rs, n) -> {
                    double rtt = rs.getDouble(5);
                    Double rttMs = rs.wasNull() ? null : rtt;
                    return new HostDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rttMs, rs.getBoolean(6), rs.getBoolean(7));
                }, id);
        return new ScanDetail(scan, hosts);
    }

    public Page<ScanDto> list(Pageable pageable) {
        Long total = jdbc.queryForObject("select count(*) from discovery_scan", Long.class);
        List<ScanDto> rows = jdbc.query(SCAN_SELECT + " order by started_at desc limit ? offset ?", (rs, n) -> toScan(rs), pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static final String SCAN_SELECT = "select id, cidr, status, requested_by, total, scanned, found, error, started_at, finished_at from discovery_scan";

    private static ScanDto toScan(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp finished = rs.getTimestamp(10);
        return new ScanDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                rs.getString(8), rs.getTimestamp(9).toInstant(), finished == null ? null : finished.toInstant());
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
