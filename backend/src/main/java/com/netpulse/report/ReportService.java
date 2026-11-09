package com.netpulse.report;

import com.netpulse.common.error.ApiException;
import com.netpulse.settings.SettingsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Per-device availability: uptime% = (UP + DEGRADED cycles) / (UP + DEGRADED + DOWN cycles).
 *
 * <p>Only cycles that observed the device count. Paused time produces no cycles, UNKNOWN means the monitor couldn't
 * tell, and cycles inside a maintenance window are left out, so none of them can raise or lower an SLA.
 *
 * <p>Recent time comes from raw cycles and older time from hourly rollups. A rollup can't be split, so an hour
 * touching a maintenance window, or only partly inside the requested range, is dropped entirely.
 */
@Service
public class ReportService {
    public record DeviceReport(UUID deviceId, String name, String host, long observedChecks, long up, long degraded, long down,
                               Double uptimePct, long observedSeconds, long downtimeSeconds, long incidents, Boolean slaMet) { }

    public record Report(Instant from, Instant to, double slaTargetPct, List<DeviceReport> devices) { }

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final Clock clock;

    public ReportService(JdbcTemplate jdbc, SettingsService settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    public Report uptime(Instant from, Instant to, double slaTarget, UUID deviceId) {
        if (!from.isBefore(to)) throw ApiException.badRequest("'from' must be before 'to'");
        if (!Double.isFinite(slaTarget) || slaTarget < 0 || slaTarget > 100) throw ApiException.badRequest("The SLA target must be between 0 and 100");
        if (deviceId != null && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from device where id = ?)", Boolean.class, deviceId))) throw ApiException.notFound("Device");
        Map<UUID, long[]> counts = new HashMap<>();

        // The first hour of raw data may already be partly cleaned up, so raw reporting starts one hour later.
        Instant rawStart = clock.instant().minus(Duration.ofDays(settings.current().rawDays())).truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
        Instant rawFrom = from.isAfter(rawStart) ? from : rawStart;
        if (rawFrom.isBefore(to)) {
            jdbc.query("""
                    select c.device_id, count(*) filter (where c.status = 'UP'), count(*) filter (where c.status = 'DEGRADED'), count(*) filter (where c.status = 'DOWN')
                    from device_check c
                    where c.checked_at >= ? and c.checked_at < ? and c.status in ('UP', 'DEGRADED', 'DOWN')
                      and not exists (select 1 from maintenance_window m where m.device_id = c.device_id and c.checked_at >= m.starts_at and c.checked_at < m.ends_at)
                    group by 1""",
                    rs -> { add(counts, rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3), rs.getLong(4)); },
                    Timestamp.from(rawFrom), Timestamp.from(to));
        }
        Instant rollupFrom = ceilHour(from);
        Instant rollupTo = to.isBefore(rawStart) ? to.truncatedTo(ChronoUnit.HOURS) : rawStart;
        if (rollupFrom.isBefore(rollupTo)) {
            jdbc.query("""
                    select r.device_id, sum(r.up), sum(r.degraded), sum(r.down)
                    from rollup_hourly r
                    where r.bucket >= ? and r.bucket < ?
                      and not exists (select 1 from maintenance_window m where m.device_id = r.device_id and m.starts_at < r.bucket + interval '1 hour' and m.ends_at > r.bucket)
                    group by 1""",
                    rs -> { add(counts, rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3), rs.getLong(4)); },
                    Timestamp.from(rollupFrom), Timestamp.from(rollupTo));
        }

        Map<UUID, Long> incidents = new HashMap<>();
        jdbc.query("select device_id, count(*) from status_event where to_status = 'DOWN' and occurred_at >= ? and occurred_at < ? group by 1",
                rs -> { incidents.put(rs.getObject(1, UUID.class), rs.getLong(2)); }, Timestamp.from(from), Timestamp.from(to));

        String where = deviceId == null ? "" : " where id = ?";
        Object[] args = deviceId == null ? new Object[0] : new Object[]{deviceId};
        List<DeviceReport> devices = jdbc.query("select id, name, host, check_interval_seconds from device" + where + " order by lower(name)", (rs, n) -> {
            UUID id = rs.getObject(1, UUID.class);
            int interval = rs.getInt(4);
            long[] c = counts.getOrDefault(id, new long[3]);
            long observed = c[0] + c[1] + c[2];
            Double pct = observed == 0 ? null : (c[0] + c[1]) * 100.0 / observed;
            return new DeviceReport(id, rs.getString(2), rs.getString(3), observed, c[0], c[1], c[2], pct, observed * interval, c[2] * interval,
                    incidents.getOrDefault(id, 0L), pct == null ? null : pct >= slaTarget);
        }, args);
        return new Report(from, to, slaTarget, devices);
    }

    private static void add(Map<UUID, long[]> m, UUID id, long up, long degraded, long down) {
        long[] c = m.computeIfAbsent(id, k -> new long[3]);
        c[0] += up;
        c[1] += degraded;
        c[2] += down;
    }

    private static Instant ceilHour(Instant t) {
        Instant floor = t.truncatedTo(ChronoUnit.HOURS);
        return floor.equals(t) ? t : floor.plus(Duration.ofHours(1));
    }
}
