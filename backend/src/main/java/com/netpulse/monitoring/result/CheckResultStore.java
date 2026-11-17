package com.netpulse.monitoring.result;

import com.netpulse.device.DeviceStatus;
import com.netpulse.monitoring.dns.DnsOutcome;
import com.netpulse.monitoring.ping.PingOutcome;
import com.netpulse.monitoring.status.Verdict;
import com.netpulse.monitoring.tcp.TcpOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write side of the time-series tables. Plain SQL rather than JPA: these rows are append-only and high-volume,
 * so entity tracking would add cost and give nothing back.
 */
@Repository
public class CheckResultStore {
    private final JdbcTemplate jdbc;

    public CheckResultStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DeviceState(DeviceStatus status, boolean enabled, String host, int failures, int successes) { }

    /** FOR UPDATE serializes this with a concurrent pause/edit of the same device. Must run in a transaction. */
    public Optional<DeviceState> lockDeviceState(UUID id) {
        return jdbc.query("select status, enabled, host, consecutive_failures, consecutive_successes from device where id = ? for update",
                (rs, n) -> new DeviceState(DeviceStatus.valueOf(rs.getString(1)), rs.getBoolean(2), rs.getString(3), rs.getInt(4), rs.getInt(5)),
                id).stream().findFirst();
    }

    public long insertCheck(UUID deviceId, Instant at, Verdict verdict, DeviceStatus status, boolean icmpBlocked,
                            String resolvedIp, String error, int durationMs) {
        return jdbc.queryForObject("""
                insert into device_check (device_id, checked_at, verdict, status, icmp_blocked, resolved_ip, error, duration_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                deviceId, ts(at), verdict.name(), status.name(), icmpBlocked, resolvedIp, truncate(error), durationMs);
    }

    public void insertPing(long checkId, PingOutcome p) {
        jdbc.update("""
                insert into ping_result (check_id, packets_sent, packets_received, loss_pct, rtt_min, rtt_avg, rtt_max, jitter, error_category, error_detail)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                checkId, p.sent(), p.received(), p.lossPct(), p.rttMin(), p.rttAvg(), p.rttMax(), p.jitter(),
                p.error() == null ? null : p.error().name(), truncate(p.errorDetail()));
    }

    public void insertTcp(long checkId, List<TcpOutcome> results) {
        for (TcpOutcome r : results) {
            jdbc.update("insert into tcp_result (check_id, port, state, connect_ms, error) values (?, ?, ?, ?, ?)",
                    checkId, r.port(), r.state().name(), r.connectMs(), truncate(r.error()));
        }
    }

    public void insertDns(long checkId, List<DnsOutcome> results) {
        for (DnsOutcome r : results) {
            jdbc.update("insert into dns_result (check_id, record_type, query_name, dns_server, outcome, resolve_ms, answers, error) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    checkId, r.recordType().name(), r.queryName(), r.dnsServer(), r.kind().name(), r.resolveMs(),
                    String.join("\n", r.answers()), truncate(r.error()));
        }
    }

    /** Written with SQL so engine updates never bump the optimistic-lock version used by user edits. */
    public void updateDeviceState(UUID id, DeviceStatus status, boolean icmpBlocked, int failures, int successes,
                                  String resolvedIp, Double rtt, Double loss, Instant checkedAt, Instant statusChangedAt) {
        jdbc.update("""
                update device set status = ?, icmp_blocked = ?, consecutive_failures = ?, consecutive_successes = ?,
                       resolved_ip = coalesce(?, resolved_ip), last_rtt_ms = ?, last_loss_pct = ?, last_checked_at = ?,
                       status_changed_at = coalesce(?, status_changed_at)
                where id = ?""",
                status.name(), icmpBlocked, failures, successes, resolvedIp, rtt, loss, ts(checkedAt), ts(statusChangedAt), id);
    }

    public void insertStatusEvent(UUID deviceId, DeviceStatus from, DeviceStatus to, String reason, Instant at) {
        jdbc.update("insert into status_event (device_id, from_status, to_status, reason, occurred_at) values (?, ?, ?, ?, ?)",
                deviceId, from.name(), to.name(), truncate(reason), ts(at));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }
}
