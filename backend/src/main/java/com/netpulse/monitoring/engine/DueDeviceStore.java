package com.netpulse.monitoring.engine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class DueDeviceStore {
    private final JdbcTemplate jdbc;

    public DueDeviceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Due(UUID id, int intervalSeconds) { }

    public List<Due> findDue(Instant now, int limit) {
        return jdbc.query("""
                select id, check_interval_seconds from device
                where enabled and (next_check_at is null or next_check_at <= ?)
                order by next_check_at nulls first limit ?""",
                (rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getInt(2)), Timestamp.from(now), limit);
    }

    public void scheduleNext(UUID id, Instant next) {
        jdbc.update("update device set next_check_at = ? where id = ?", Timestamp.from(next), id);
    }
}
