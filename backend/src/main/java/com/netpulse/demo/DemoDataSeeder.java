package com.netpulse.demo;

import com.netpulse.device.*;
import com.netpulse.monitoring.retention.RollupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * SYNTHETIC data for screenshots. Only exists in the "demo" profile, which uses its own database and turns the
 * monitoring engine off, so nothing here can mix with real results. Hosts are RFC 5737 documentation addresses
 * and reserved example domains. Every device is tagged "demo" and the UI shows a permanent banner.
 */
@Component
@Profile("demo")
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record Spec(String name, String host, DeviceType type, String location, double baseRtt, double degradedChance,
                        int outageHoursAgo, int outageMinutes, DeviceStatus finalStatus, List<Integer> ports) { }

    private static final List<Spec> SPECS = List.of(
            new Spec("core-rtr-01", "192.0.2.1", DeviceType.ROUTER, "DC1 / Rack A1", 1.8, 0.002, 0, 0, DeviceStatus.UP, List.of(22)),
            new Spec("dist-sw-01", "192.0.2.2", DeviceType.SWITCH, "DC1 / Rack A2", 2.4, 0.004, 0, 0, DeviceStatus.UP, List.of(22)),
            new Spec("dist-sw-02", "192.0.2.3", DeviceType.SWITCH, "DC1 / Rack B2", 2.9, 0.004, 52, 25, DeviceStatus.UP, List.of(22)),
            new Spec("access-sw-3f", "192.0.2.14", DeviceType.SWITCH, "Office / Floor 3", 4.6, 0.01, 0, 0, DeviceStatus.UP, List.of(22)),
            new Spec("access-sw-4f", "192.0.2.15", DeviceType.SWITCH, "Office / Floor 4", 5.1, 0.02, 1, 60, DeviceStatus.DOWN, List.of(22)),
            new Spec("edge-fw-01", "192.0.2.254", DeviceType.FIREWALL, "DC1 / Rack A1", 1.5, 0.003, 0, 0, DeviceStatus.UP, List.of(443)),
            new Spec("dns-int-01", "198.51.100.53", DeviceType.SERVER, "DC1 / Rack C4", 3.2, 0.005, 0, 0, DeviceStatus.UP, List.of(53)),
            new Spec("dns-int-02", "198.51.100.54", DeviceType.SERVER, "DC2 / Rack A1", 11.7, 0.01, 130, 15, DeviceStatus.UP, List.of(53)),
            new Spec("app-srv-01", "198.51.100.20", DeviceType.SERVER, "DC1 / Rack C1", 3.9, 0.45, 0, 0, DeviceStatus.DEGRADED, List.of(22, 443)),
            new Spec("www.example.com", "www.example.com", DeviceType.WEBSITE, "Public internet", 38.0, 0.02, 0, 0, DeviceStatus.UP, List.of(80, 443)),
            new Spec("shop.example.net", "shop.example.net", DeviceType.WEBSITE, "Public internet", 72.0, 0.03, 20, 10, DeviceStatus.UP, List.of(443)));

    private final DeviceRepository devices;
    private final JdbcTemplate jdbc;
    private final RollupService rollups;
    private final Clock clock;

    public DemoDataSeeder(DeviceRepository devices, JdbcTemplate jdbc, RollupService rollups, Clock clock) {
        this.devices = devices;
        this.jdbc = jdbc;
        this.rollups = rollups;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (devices.count() > 0) return;
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Instant start = end.minus(Duration.ofDays(7));
        for (Spec s : SPECS) seed(s, start, end);
        rollups.rollup(start, end.plusSeconds(1));
        log.info("Seeded {} synthetic demo devices", SPECS.size());
    }

    private void seed(Spec s, Instant start, Instant end) {
        Device d = new Device();
        d.setName(s.name());
        d.setHost(s.host());
        d.setType(s.type());
        d.setLocation(s.location());
        d.setDescription("Synthetic demo device - not a real host");
        d.setCheckIntervalSeconds(300);
        d.setTags(new java.util.HashSet<>(Set.of("demo", s.type().name().toLowerCase())));
        s.ports().forEach(p -> d.getPorts().add(new DevicePort(d, p, null)));
        boolean down = s.finalStatus() == DeviceStatus.DOWN;
        d.setStatus(s.finalStatus());
        d.setLastRttMs(down ? null : s.baseRtt() * (s.finalStatus() == DeviceStatus.DEGRADED ? 40 : 1.05));
        d.setLastLossPct(down ? 100.0 : 0.0);
        d.setLastCheckedAt(end);
        d.setResolvedIp(Character.isDigit(s.host().charAt(0)) ? s.host() : "203.0.113.10");
        d.setCreatedAt(start);
        d.setStatusChangedAt(s.outageMinutes() > 0 ? end.minus(Duration.ofHours(s.outageHoursAgo())) : start);
        devices.saveAndFlush(d);

        Instant outageStart = end.minus(Duration.ofHours(s.outageHoursAgo()));
        // A device that is DOWN now is still inside its outage; one that is DEGRADED now has been for 40 minutes.
        Instant outageEnd = down ? end.plusSeconds(60) : outageStart.plus(Duration.ofMinutes(s.outageMinutes()));
        Instant degradedFrom = s.finalStatus() == DeviceStatus.DEGRADED ? end.minus(Duration.ofMinutes(40)) : end.plus(Duration.ofDays(1));
        jdbc.update("""
                with series as (
                    select t, case when t >= ? and t < ? then 'DOWN'
                                   when t >= ? or random() < ? then 'DEGRADED' else 'UP' end as st
                    from generate_series(?::timestamptz, ?::timestamptz, interval '5 minutes') t)
                insert into device_check (device_id, checked_at, verdict, status, error, duration_ms)
                select ?, t, case st when 'DOWN' then 'FAIL' else st end, st,
                       case st when 'UP' then 'All checks passed' when 'DOWN' then 'All checks failed (demo)' else 'High latency (demo)' end,
                       3000 + (random() * 3000)::int
                from series""",
                Timestamp.from(outageStart), Timestamp.from(outageEnd), Timestamp.from(degradedFrom), s.degradedChance(), Timestamp.from(start), Timestamp.from(end), d.getId());
        jdbc.update("""
                insert into ping_result (check_id, packets_sent, packets_received, loss_pct, rtt_min, rtt_avg, rtt_max, jitter)
                select id, 4, r, (4 - r) * 25.0,
                       case when r > 0 then a * 0.9 end, case when r > 0 then a end, case when r > 0 then a * 1.2 end,
                       case when r > 0 then a * 0.08 end
                from (select id, case status when 'DOWN' then 0 when 'DEGRADED' then 3 else 4 end r,
                             ? * (1 + random() * 0.25) * (case status when 'DEGRADED' then 40 else 1 end) a
                      from device_check where device_id = ?) x""", s.baseRtt(), d.getId());
        for (int port : s.ports()) {
            jdbc.update("""
                    insert into tcp_result (check_id, port, state, connect_ms)
                    select id, ?, case when status = 'DOWN' then 'FILTERED_OR_UNKNOWN' else 'OPEN' end,
                           case when status = 'DOWN' then null else ? end
                    from device_check where device_id = ? order by checked_at desc limit 1""", port, s.baseRtt() * 1.1, d.getId());
        }
        if (s.outageMinutes() > 0) {
            insertEvent(d, "UP", "DOWN", "All checks failed (demo)", outageStart);
            if (s.finalStatus() != DeviceStatus.DOWN) insertEvent(d, "DOWN", "UP", "All checks passed", outageEnd);
        }
        if (s.finalStatus() == DeviceStatus.DEGRADED) insertEvent(d, "UP", "DEGRADED", "High latency (demo)", end.minus(Duration.ofMinutes(40)));
    }

    private void insertEvent(Device d, String from, String to, String reason, Instant at) {
        jdbc.update("insert into status_event (device_id, from_status, to_status, reason, occurred_at) values (?, ?, ?, ?, ?)",
                d.getId(), from, to, reason, Timestamp.from(at));
    }
}
