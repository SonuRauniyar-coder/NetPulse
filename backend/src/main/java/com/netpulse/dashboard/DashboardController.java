package com.netpulse.dashboard;

import com.netpulse.alert.AlertService;
import com.netpulse.dashboard.DashboardDtos.SlowDevice;
import com.netpulse.dashboard.DashboardDtos.Summary;
import com.netpulse.monitoring.result.MetricRange;
import com.netpulse.monitoring.result.MonitoringDtos.MetricPoint;
import com.netpulse.monitoring.result.MonitoringQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AlertService alerts;

    public DashboardController(JdbcTemplate jdbc, Clock clock, AlertService alerts) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public Summary summary() {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select status, count(*) from device group by status", rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        Double avg = jdbc.queryForObject("select avg(last_rtt_ms) from device where enabled and last_rtt_ms is not null", Double.class);
        Double uptime = jdbc.queryForObject("""
                select 100.0 * count(*) filter (where status in ('UP','DEGRADED'))
                       / nullif(count(*) filter (where status in ('UP','DEGRADED','DOWN')), 0)
                from device_check where checked_at >= ?""", Double.class,
                Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
        List<SlowDevice> slowest = jdbc.query("""
                select id, name, host, status, last_rtt_ms from device
                where enabled and last_rtt_ms is not null order by last_rtt_ms desc limit 5""",
                (rs, n) -> new SlowDevice(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5)));
        var recent = jdbc.query(MonitoringQueryService.EVENT_SELECT + " order by e.occurred_at desc limit 10", MonitoringQueryService.EVENT);

        return new Summary(total, counts.getOrDefault("UP", 0L), counts.getOrDefault("DEGRADED", 0L), counts.getOrDefault("DOWN", 0L),
                counts.getOrDefault("UNKNOWN", 0L), counts.getOrDefault("PAUSED", 0L), avg, uptime, slowest, recent,
                alerts.summary().active(), alerts.recent(6));
    }

    /** Average latency across all devices, in the same buckets as the per-device charts. */
    @GetMapping("/latency")
    public List<MetricPoint> latency(@RequestParam(defaultValue = "1h") String range) {
        MetricRange r = MetricRange.parse(range);
        Timestamp from = Timestamp.from(clock.instant().minus(r.span));
        if (r.useRollups) {
            return jdbc.query("""
                    select bucket, avg(rtt_avg), min(rtt_min), max(rtt_max), avg(loss_avg), avg(jitter_avg)
                    from rollup_hourly where bucket >= ? group by 1 order by 1""", MonitoringQueryService.POINT, from);
        }
        return jdbc.query("""
                select date_bin(?::interval, c.checked_at, timestamptz '2000-01-01') t,
                       avg(p.rtt_avg), min(p.rtt_min), max(p.rtt_max), avg(p.loss_pct), avg(p.jitter)
                from device_check c join ping_result p on p.check_id = c.id
                where c.checked_at >= ? group by 1 order by 1""", MonitoringQueryService.POINT, r.bucket, from);
    }
}
