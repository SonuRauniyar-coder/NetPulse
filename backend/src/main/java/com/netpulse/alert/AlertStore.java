package com.netpulse.alert;

import com.netpulse.alert.AlertTypes.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class AlertStore {
    private static final String RULE_COLUMNS = "id, name, type, severity, device_id, threshold, port, interface_name, duration_seconds, enabled";

    static final RowMapper<Rule> RULE = (rs, n) -> new Rule(rs.getObject(1, UUID.class), rs.getString(2), RuleType.valueOf(rs.getString(3)), Severity.valueOf(rs.getString(4)),
            rs.getObject(5, UUID.class), rs.getObject(6, Double.class), rs.getObject(7, Integer.class), rs.getString(8), rs.getInt(9), rs.getBoolean(10));

    private final JdbcTemplate jdbc;

    public AlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Open(UUID id, UUID ruleId, String subject, Severity severity, State state, String deviceName, String message) { }

    /** Serializes evaluations of one device (the ping cycle and the SNMP poll run independently). Released at commit. */
    public void lockDevice(UUID deviceId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 7))", rs -> { }, deviceId.toString());
    }

    public List<Rule> rulesFor(UUID deviceId, Set<RuleType> types) {
        String in = String.join(",", types.stream().map(t -> "'" + t.name() + "'").toList());   // enum constants, never user input
        return jdbc.query("select " + RULE_COLUMNS + " from alert_rule where enabled and (device_id is null or device_id = ?) and type in (" + in + ")", RULE, deviceId);
    }

    public Optional<Rule> findRule(UUID id) {
        return jdbc.query("select " + RULE_COLUMNS + " from alert_rule where id = ?", RULE, id).stream().findFirst();
    }

    private static final String OPEN_SELECT = """
            select a.id, a.rule_id, a.subject, a.severity, a.state, d.name, a.message
            from alert a join device d on d.id = a.device_id where a.state <> 'RESOLVED'""";

    private static final RowMapper<Open> OPEN = (rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            Severity.valueOf(rs.getString(4)), State.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7));

    public List<Open> openFor(UUID deviceId) {
        return jdbc.query(OPEN_SELECT + " and a.device_id = ?", OPEN, deviceId);
    }

    public List<Open> openForRule(UUID ruleId) {
        return jdbc.query(OPEN_SELECT + " and a.rule_id = ?", OPEN, ruleId);
    }

    public Map<String, Instant> pendingFor(UUID deviceId) {
        Map<String, Instant> out = new HashMap<>();
        jdbc.query("select rule_id, subject, breaching_since from alert_pending where device_id = ?",
                rs -> { out.put(key(rs.getObject(1, UUID.class), rs.getString(2)), rs.getTimestamp(3).toInstant()); }, deviceId);
        return out;
    }

    public void startPending(UUID ruleId, UUID deviceId, String subject, Instant since) {
        jdbc.update("insert into alert_pending (rule_id, device_id, subject, breaching_since) values (?, ?, ?, ?) on conflict do nothing",
                ruleId, deviceId, subject, Timestamp.from(since));
    }

    public void clearPending(UUID ruleId, UUID deviceId, String subject) {
        jdbc.update("delete from alert_pending where rule_id = ? and device_id = ? and subject = ?", ruleId, deviceId, subject);
    }

    public void clearPendingForRule(UUID ruleId) {
        jdbc.update("delete from alert_pending where rule_id = ?", ruleId);
    }

    public void clearPendingForDevice(UUID deviceId) {
        jdbc.update("delete from alert_pending where device_id = ?", deviceId);
    }

    public void insertAlert(UUID id, Rule rule, UUID deviceId, String subject, String message, Double value, Instant at) {
        jdbc.update("""
                insert into alert (id, rule_id, rule_name, device_id, subject, severity, state, message, value, triggered_at)
                values (?, ?, ?, ?, ?, ?, 'TRIGGERED', ?, ?, ?)""",
                id, rule.id(), rule.name(), deviceId, subject, rule.severity().name(), truncate(message, 500), value, Timestamp.from(at));
    }

    public void resolve(UUID id, String reason, Instant at) {
        jdbc.update("update alert set state = 'RESOLVED', resolved_at = ?, resolve_reason = ? where id = ? and state <> 'RESOLVED'",
                Timestamp.from(at), truncate(reason, 255), id);
    }

    public boolean inMaintenance(UUID deviceId, Instant at) {
        Timestamp t = Timestamp.from(at);
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from maintenance_window where device_id = ? and starts_at <= ? and ends_at > ?)", Boolean.class, deviceId, t, t));
    }

    static String key(UUID ruleId, String subject) {
        return ruleId + "|" + subject;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
