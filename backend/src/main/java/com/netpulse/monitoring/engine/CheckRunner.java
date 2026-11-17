package com.netpulse.monitoring.engine;

import com.netpulse.alert.AlertEngine;
import com.netpulse.alert.RuleEvaluator;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.net.InvalidTargetException;
import com.netpulse.common.net.TargetValidator;
import com.netpulse.monitoring.dns.DnsChecker;
import com.netpulse.monitoring.dns.DnsOutcome;
import com.netpulse.monitoring.dns.DnsResultKind;
import com.netpulse.monitoring.ping.PingErrorCategory;
import com.netpulse.monitoring.ping.PingOutcome;
import com.netpulse.monitoring.ping.PingService;
import com.netpulse.monitoring.result.CheckRecorder;
import com.netpulse.monitoring.status.Observation;
import com.netpulse.monitoring.tcp.TcpChecker;
import com.netpulse.monitoring.tcp.TcpOutcome;
import com.netpulse.monitoring.tcp.TcpState;
import com.netpulse.realtime.LivePublisher;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Runs one full monitoring cycle for one device: resolve, probe in parallel, evaluate, store, publish. */
@Service
public class CheckRunner {
    private final DeviceSnapshotLoader loader;
    private final TargetValidator validator;
    private final PingService ping;
    private final TcpChecker tcp;
    private final DnsChecker dns;
    private final CheckRecorder recorder;
    private final LivePublisher publisher;
    private final AlertEngine alerts;
    private final ExecutorService executor;
    private final MonitoringMetrics metrics;
    private final Duration cycleTimeout;
    private final Clock clock;

    public CheckRunner(DeviceSnapshotLoader loader, TargetValidator validator, PingService ping, TcpChecker tcp,
                       DnsChecker dns, CheckRecorder recorder, LivePublisher publisher, AlertEngine alerts, ExecutorService monitoringExecutor,
                       MonitoringMetrics metrics, NetPulseProperties props, Clock clock) {
        this.loader = loader;
        this.validator = validator;
        this.ping = ping;
        this.tcp = tcp;
        this.dns = dns;
        this.recorder = recorder;
        this.publisher = publisher;
        this.alerts = alerts;
        this.executor = monitoringExecutor;
        this.metrics = metrics;
        this.cycleTimeout = props.monitoring().cycleTimeout();
        this.clock = clock;
    }

    public void run(UUID deviceId) {
        Optional<DeviceSnapshot> found = loader.load(deviceId);
        if (found.isEmpty()) return;
        DeviceSnapshot device = found.get();

        Instant started = clock.instant();
        long startNanos = System.nanoTime();
        long deadline = startNanos + cycleTimeout.toNanos();

        // Resolve once, validate the result, and use only that address below (DNS rebinding guard).
        ResolveResult resolved = await(executor.submit(() -> resolve(device.host())), deadline,
                new ResolveResult(null, PingErrorCategory.TIMEOUT, "Timed out resolving host"));

        Observation observation = resolved.address() == null
                ? unresolved(device, resolved)
                : probe(device, resolved.address(), deadline);

        int durationMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
        String ip = resolved.address() == null ? null : resolved.address().getHostAddress();
        recorder.record(device.id(), device.host(), observation, ip, started, durationMs).ifPresent(r -> {
            metrics.recordCycle(r.assessment().verdict(), durationMs);
            publisher.publishCheck(device, r);
            // After commit, so an alert is only ever raised for a result that really was stored.
            Map<Integer, TcpState> ports = new HashMap<>();
            observation.tcp().forEach(t -> ports.put(t.port(), t.state()));
            alerts.onCheck(device.id(), new RuleEvaluator.CheckFacts(device.name(), r.status(), r.rttMs(), r.lossPct(), ports));
        });
    }

    private record ResolveResult(InetAddress address, PingErrorCategory error, String detail) { }

    private ResolveResult resolve(String host) {
        try {
            return new ResolveResult(validator.resolve(host), null, null);
        } catch (UnknownHostException e) {
            return new ResolveResult(null, PingErrorCategory.DNS_FAILURE, "Could not resolve " + host);
        } catch (InvalidTargetException e) {
            return new ResolveResult(null, PingErrorCategory.UNKNOWN, "Resolved to a forbidden address: " + e.getMessage());
        }
    }

    private Observation unresolved(DeviceSnapshot d, ResolveResult r) {
        List<TcpOutcome> tcpFailed = d.ports().stream()
                .map(p -> new TcpOutcome(p, TcpState.FILTERED_OR_UNKNOWN, null, r.detail())).toList();
        return new Observation(PingOutcome.failure(r.error(), r.detail(), 0), tcpFailed,
                d.dnsChecks().stream().map(c -> timedOutDns(c, r.detail())).toList(), d.host());
    }

    private Observation probe(DeviceSnapshot d, InetAddress address, long deadline) {
        int probes = ping.probeCount();
        Future<PingOutcome> pingFuture = submit(() -> ping.ping(address));
        List<Future<TcpOutcome>> tcpFutures = new ArrayList<>();
        for (int port : d.ports()) tcpFutures.add(submit(() -> tcp.check(address, port)));
        List<Future<DnsOutcome>> dnsFutures = new ArrayList<>();
        for (var c : d.dnsChecks()) dnsFutures.add(submit(() -> dns.check(c.type(), c.queryName(), c.server(), address)));

        // Every wait is bounded by the same cycle deadline: one slow probe can never stall the device's cycle.
        PingOutcome p = await(pingFuture, deadline, PingOutcome.failure(PingErrorCategory.TIMEOUT, "Cycle deadline exceeded", probes));
        List<TcpOutcome> tcpResults = new ArrayList<>();
        for (int i = 0; i < tcpFutures.size(); i++) {
            tcpResults.add(await(tcpFutures.get(i), deadline,
                    new TcpOutcome(d.ports().get(i), TcpState.FILTERED_OR_UNKNOWN, null, "Cycle deadline exceeded")));
        }
        List<DnsOutcome> dnsResults = new ArrayList<>();
        for (int i = 0; i < dnsFutures.size(); i++) {
            dnsResults.add(await(dnsFutures.get(i), deadline, timedOutDns(d.dnsChecks().get(i), "Cycle deadline exceeded")));
        }
        return new Observation(p, tcpResults, dnsResults, d.host());
    }

    private <T> Future<T> submit(Supplier<T> task) {
        return executor.submit(task::get);
    }

    private static DnsOutcome timedOutDns(DeviceSnapshot.DnsSpec c, String detail) {
        return new DnsOutcome(c.type(), c.queryName() == null ? "(device ip)" : c.queryName(), c.server(),
                DnsResultKind.TIMEOUT, null, List.of(), detail);
    }

    private static <T> T await(Future<T> future, long deadlineNanos, T fallback) {
        try {
            return future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);   // interrupts the virtual thread; ProcessRunner then kills the OS process
            return fallback;
        } catch (ExecutionException e) {
            return fallback;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return fallback;
        }
    }
}
