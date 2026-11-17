package com.netpulse.monitoring.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Aggregates raw cycles into hourly rows. The upsert makes it idempotent: re-running an hour simply recomputes
 * it, so the still-open current hour can be refreshed repeatedly and a crashed run needs no cleanup.
 */
@Service
public class RollupService {
    private final JdbcTemplate jdbc;

    public RollupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int rollup(Instant from, Instant to) {
        return jdbc.update("""
                insert into rollup_hourly (device_id, bucket, checks, up, degraded, down, rtt_avg, rtt_min, rtt_max, loss_avg, jitter_avg)
                select c.device_id, date_trunc('hour', c.checked_at, 'UTC'), count(*),
                       count(*) filter (where c.status = 'UP'), count(*) filter (where c.status = 'DEGRADED'),
                       count(*) filter (where c.status = 'DOWN'),
                       avg(p.rtt_avg), min(p.rtt_min), max(p.rtt_max), avg(p.loss_pct), avg(p.jitter)
                from device_check c left join ping_result p on p.check_id = c.id
                where c.checked_at >= date_trunc('hour', ?::timestamptz, 'UTC') and c.checked_at < ?
                group by 1, 2
                on conflict (device_id, bucket) do update set
                    checks = excluded.checks, up = excluded.up, degraded = excluded.degraded, down = excluded.down,
                    rtt_avg = excluded.rtt_avg, rtt_min = excluded.rtt_min, rtt_max = excluded.rtt_max,
                    loss_avg = excluded.loss_avg, jitter_avg = excluded.jitter_avg""",
                Timestamp.from(from), Timestamp.from(to));
    }

    public Instant latestBucketOr(Instant fallback) {
        Timestamp t = jdbc.queryForObject("select max(bucket) from rollup_hourly", Timestamp.class);
        return t == null ? fallback : t.toInstant();
    }
}
