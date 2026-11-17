package com.netpulse.monitoring.result;

import com.netpulse.common.error.ApiException;
import com.netpulse.device.DeviceRepository;
import com.netpulse.monitoring.result.MonitoringDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Read side of the time-series data. Everything here is computed from stored measurements. */
@Service
public class MonitoringQueryService {
    private static final int SEGMENTS = 48;
    private static final Duration SEGMENT = Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;
    private final DeviceRepository devices;
    private final Clock clock;

    public MonitoringQueryService(JdbcTemplate jdbc, DeviceRepository devices, Clock clock) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.clock = clock;
    }

    public List<MetricPoint> metrics(UUID deviceId, MetricRange range) {
        requireDevice(deviceId);
        Timestamp from = Timestamp.from(clock.instant().minus(range.span));
        if (range.useRollups) {
            return jdbc.query("""
                    select bucket, rtt_avg, rtt_min, rtt_max, loss_avg, jitter_avg from rollup_hourly
                    where device_id = ? and bucket >= ? order by bucket""", POINT, deviceId, from);
        }
        return jdbc.query("""
                select date_bin(?::interval, c.checked_at, timestamptz '2000-01-01') t,
                       avg(p.rtt_avg), min(p.rtt_min), max(p.rtt_max), avg(p.loss_pct), avg(p.jitter)
                from device_check c join ping_result p on p.check_id = c.id
                where c.device_id = ? and c.checked_at >= ?
                group by 1 order by 1""", POINT, range.bucket, deviceId, from);
    }

    public Page<CheckSummary> checks(UUID deviceId, Pageable pageable) {
        requireDevice(deviceId);
        Long total = jdbc.queryForObject("select count(*) from device_check where device_id = ?", Long.class, deviceId);
        List<CheckSummary> rows = jdbc.query(CHECK_SELECT + " where c.device_id = ? order by c.checked_at desc limit ? offset ?",
                CHECK, deviceId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public Optional<CheckDetail> latest(UUID deviceId) {
        requireDevice(deviceId);
        return jdbc.query(CHECK_SELECT + " where c.device_id = ? order by c.checked_at desc limit 1", CHECK, deviceId)
                .stream().findFirst().map(c -> new CheckDetail(c,
                        jdbc.query("select port, state, connect_ms, error from tcp_result where check_id = ? order by port",
                                (rs, n) -> new TcpDetail(rs.getInt(1), rs.getString(2), dbl(rs, 3), rs.getString(4)), c.id()),
                        jdbc.query("select record_type, query_name, dns_server, outcome, resolve_ms, answers, error from dns_result where check_id = ? order by id",
                                (rs, n) -> new DnsDetail(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), dbl(rs, 5),
                                        splitAnswers(rs.getString(6)), rs.getString(7)), c.id())));
    }

    /** 24h status-page bar: 48 half-hour segments aligned to the clock; a segment shows its worst status. */
    public UptimeResponse uptime(UUID deviceId) {
        requireDevice(deviceId);
        long segmentSeconds = SEGMENT.toSeconds();
        long alignedEnd = (clock.instant().getEpochSecond() / segmentSeconds + 1) * segmentSeconds;
        Instant start = Instant.ofEpochSecond(alignedEnd - SEGMENTS * segmentSeconds);

        Map<Instant, int[]> byBucket = new HashMap<>();
        jdbc.query("""
                select date_bin('30 minutes', checked_at, timestamptz '2000-01-01') b,
                       count(*) filter (where status = 'UP'), count(*) filter (where status = 'DEGRADED'),
                       count(*) filter (where status = 'DOWN')
                from device_check where device_id = ? and checked_at >= ? group by 1""",
                rs -> { byBucket.put(rs.getTimestamp(1).toInstant(), new int[]{rs.getInt(2), rs.getInt(3), rs.getInt(4)}); },
                deviceId, Timestamp.from(start));

        List<UptimeSegment> segments = new ArrayList<>(SEGMENTS);
        long good = 0, observed = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            Instant s = start.plus(SEGMENT.multipliedBy(i));
            int[] c = byBucket.getOrDefault(s, new int[3]);
            int total = c[0] + c[1] + c[2];
            String status = total == 0 ? null : c[2] > 0 ? "DOWN" : c[1] > 0 ? "DEGRADED" : "UP";
            segments.add(new UptimeSegment(s, s.plus(SEGMENT), status, total, c[0], c[1], c[2]));
            good += c[0] + c[1];
            observed += total;
        }
        return new UptimeResponse(observed == 0 ? null : good * 100.0 / observed, segments);
    }

    public Page<StatusEventDto> events(UUID deviceId, Pageable pageable) {
        requireDevice(deviceId);
        Long total = jdbc.queryForObject("select count(*) from status_event where device_id = ?", Long.class, deviceId);
        List<StatusEventDto> rows = jdbc.query(EVENT_SELECT + " where e.device_id = ? order by e.occurred_at desc limit ? offset ?",
                EVENT, deviceId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private void requireDevice(UUID id) {
        if (!devices.existsById(id)) throw ApiException.notFound("Device");
    }

    private static final String CHECK_SELECT = """
            select c.id, c.checked_at, c.verdict, c.status, c.icmp_blocked, c.resolved_ip, c.error, c.duration_ms,
                   p.packets_sent, p.packets_received, p.loss_pct, p.rtt_min, p.rtt_avg, p.rtt_max, p.jitter, p.error_category, p.error_detail
            from device_check c left join ping_result p on p.check_id = c.id""";

    public static final String EVENT_SELECT = """
            select e.id, e.device_id, d.name, e.from_status, e.to_status, e.reason, e.occurred_at
            from status_event e join device d on d.id = e.device_id""";

    public static final RowMapper<StatusEventDto> EVENT = (rs, n) -> new StatusEventDto(rs.getLong(1), rs.getObject(2, UUID.class),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant());

    public static final RowMapper<MetricPoint> POINT = (rs, n) -> new MetricPoint(rs.getTimestamp(1).toInstant(),
            dbl(rs, 2), dbl(rs, 3), dbl(rs, 4), dbl(rs, 5), dbl(rs, 6));

    private static final RowMapper<CheckSummary> CHECK = (rs, n) -> {
        rs.getInt(9);
        PingDetail ping = rs.wasNull() ? null : new PingDetail(rs.getInt(9), rs.getInt(10), rs.getDouble(11), dbl(rs, 12),
                dbl(rs, 13), dbl(rs, 14), dbl(rs, 15), rs.getString(16), rs.getString(17));
        return new CheckSummary(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                rs.getString(6), rs.getString(7), rs.getInt(8), ping);
    };

    private static Double dbl(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static List<String> splitAnswers(String s) {
        return s == null || s.isEmpty() ? List.of() : List.of(s.split("\n"));
    }
}
