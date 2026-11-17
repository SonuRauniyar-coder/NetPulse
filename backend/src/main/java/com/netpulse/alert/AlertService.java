package com.netpulse.alert;

import com.netpulse.alert.AlertTypes.*;
import com.netpulse.audit.AuditService;
import com.netpulse.common.error.ApiException;
import com.netpulse.common.web.CurrentUser;
import com.netpulse.device.DeviceRepository;
import com.netpulse.notification.NotificationOutbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AlertService {
    public record AlertDto(UUID id, UUID ruleId, String ruleName, UUID deviceId, String deviceName, String subject, Severity severity, State state,
                           String message, Double value, Instant triggeredAt, Instant acknowledgedAt, String acknowledgedBy, Instant resolvedAt, String resolveReason) { }

    public record Summary(long triggered, long acknowledged, long critical) {
        public long active() { return triggered + acknowledged; }
    }

    public record RuleInput(String name, RuleType type, Severity severity, UUID deviceId, Double threshold, Integer port, String interfaceName,
                            int durationSeconds, boolean enabled) { }

    public record RuleDto(UUID id, String name, RuleType type, Severity severity, UUID deviceId, String deviceName, Double threshold, Integer port,
                          String interfaceName, int durationSeconds, boolean enabled) { }

    public record WindowDto(UUID id, UUID deviceId, String deviceName, Instant startsAt, Instant endsAt, String reason, String createdBy) { }

    private static final String ALERT_SELECT = """
            select a.id, a.rule_id, a.rule_name, a.device_id, d.name, a.subject, a.severity, a.state, a.message, a.value,
                   a.triggered_at, a.acknowledged_at, a.acknowledged_by, a.resolved_at, a.resolve_reason
            from alert a join device d on d.id = a.device_id""";

    static final RowMapper<AlertDto> ALERT = (rs, n) -> new AlertDto(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
            rs.getString(5), rs.getString(6), Severity.valueOf(rs.getString(7)), State.valueOf(rs.getString(8)), rs.getString(9), rs.getObject(10, Double.class),
            rs.getTimestamp(11).toInstant(), instant(rs.getTimestamp(12)), rs.getString(13), instant(rs.getTimestamp(14)), rs.getString(15));

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }

    private final JdbcTemplate jdbc;
    private final AlertStore store;
    private final AlertEngine engine;
    private final NotificationOutbox outbox;
    private final DeviceRepository devices;
    private final AuditService audit;
    private final Clock clock;

    public AlertService(JdbcTemplate jdbc, AlertStore store, AlertEngine engine, NotificationOutbox outbox, DeviceRepository devices, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.engine = engine;
        this.outbox = outbox;
        this.devices = devices;
        this.audit = audit;
        this.clock = clock;
    }

    public Page<AlertDto> list(State state, UUID deviceId, Pageable pageable) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        if (state != null) { where.append(" and a.state = ?"); args.add(state.name()); }
        if (deviceId != null) { where.append(" and a.device_id = ?"); args.add(deviceId); }
        Long total = jdbc.queryForObject("select count(*) from alert a" + where, Long.class, args.toArray());
        args.add(pageable.getPageSize());
        args.add(pageable.getOffset());
        List<AlertDto> rows = jdbc.query(ALERT_SELECT + where + " order by a.triggered_at desc, a.id limit ? offset ?", ALERT, args.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public List<AlertDto> recent(int limit) {
        return jdbc.query(ALERT_SELECT + " order by a.triggered_at desc, a.id limit ?", ALERT, limit);
    }

    public Summary summary() {
        return jdbc.queryForObject("""
                select count(*) filter (where state = 'TRIGGERED'), count(*) filter (where state = 'ACKNOWLEDGED'),
                       count(*) filter (where severity = 'CRITICAL')
                from alert where state <> 'RESOLVED'""", (rs, n) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    @Transactional
    public AlertDto acknowledge(UUID id) {
        State state = jdbc.query("select state from alert where id = ? for update", (rs, n) -> State.valueOf(rs.getString(1)), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Alert"));
        AlertDecider.requireAcknowledgeable(state);
        Instant now = clock.instant();
        String by = CurrentUser.name();
        jdbc.update("update alert set state = 'ACKNOWLEDGED', acknowledged_at = ?, acknowledged_by = ? where id = ?", Timestamp.from(now), by, id);
        AlertDto a = jdbc.queryForObject(ALERT_SELECT + " where a.id = ?", ALERT, id);
        outbox.enqueue(id, a.severity(), State.ACKNOWLEDGED, a.message() + " (acknowledged by " + by + ")", now);
        audit.record("ALERT_ACK", "alert", id, "Acknowledged: " + a.message());
        engine.publishAfterCommit();
        return a;
    }

    public List<RuleDto> rules() {
        return jdbc.query("""
                select r.id, r.name, r.type, r.severity, r.device_id, d.name, r.threshold, r.port, r.interface_name, r.duration_seconds, r.enabled
                from alert_rule r left join device d on d.id = r.device_id order by lower(r.name)""",
                (rs, n) -> new RuleDto(rs.getObject(1, UUID.class), rs.getString(2), RuleType.valueOf(rs.getString(3)), Severity.valueOf(rs.getString(4)), rs.getObject(5, UUID.class),
                        rs.getString(6), rs.getObject(7, Double.class), rs.getObject(8, Integer.class), rs.getString(9), rs.getInt(10), rs.getBoolean(11)));
    }

    @Transactional
    public UUID createRule(RuleInput in) {
        validate(in);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into alert_rule (id, name, type, severity, device_id, threshold, port, interface_name, duration_seconds, enabled)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, in.name().trim(), in.type().name(), in.severity().name(), in.deviceId(), in.threshold(), port(in), iface(in), in.durationSeconds(), in.enabled());
        audit.record("RULE_CREATE", "alert_rule", id, "Created rule '" + in.name().trim() + "' (" + in.type() + ", " + in.severity() + ")");
        return id;
    }

    @Transactional
    public void updateRule(UUID id, RuleInput in) {
        Rule before = store.findRule(id).orElseThrow(() -> ApiException.notFound("Alert rule"));
        validate(in);
        jdbc.update("""
                update alert_rule set name = ?, type = ?, severity = ?, device_id = ?, threshold = ?, port = ?, interface_name = ?,
                    duration_seconds = ?, enabled = ?, updated_at = now() where id = ?""",
                in.name().trim(), in.type().name(), in.severity().name(), in.deviceId(), in.threshold(), port(in), iface(in), in.durationSeconds(), in.enabled(), id);
        // If the rule now means something else, alerts it raised under the old meaning would never clear.
        boolean changedScope = before.type() != in.type() || !java.util.Objects.equals(before.deviceId(), in.deviceId());
        if (!in.enabled() || changedScope) {
            engine.resolveForRule(id, in.enabled() ? "Rule changed" : "Rule disabled");
        } else {
            store.clearPendingForRule(id);
        }
        audit.record("RULE_UPDATE", "alert_rule", id, "Updated rule '" + in.name().trim() + "' (" + in.type() + ", " + in.severity() + (in.enabled() ? "" : ", disabled") + ")");
    }

    @Transactional
    public void deleteRule(UUID id) {
        Rule r = store.findRule(id).orElseThrow(() -> ApiException.notFound("Alert rule"));
        engine.resolveForRule(id, "Rule deleted");
        jdbc.update("delete from alert_rule where id = ?", id);
        audit.record("RULE_DELETE", "alert_rule", id, "Deleted rule '" + r.name() + "'");
    }

    private void validate(RuleInput in) {
        if (in.type().needsThreshold() && in.threshold() == null) throw ApiException.badRequest("This rule type needs a threshold");
        if (in.threshold() != null && !Double.isFinite(in.threshold())) throw ApiException.badRequest("Threshold must be a number");
        if (in.threshold() != null && in.threshold() < 0) throw ApiException.badRequest("Threshold can't be negative");
        boolean percent = in.type() == RuleType.LOSS_ABOVE || in.type() == RuleType.INTERFACE_UTILIZATION;
        if (percent && in.threshold() != null && in.threshold() > 100) throw ApiException.badRequest("A percentage threshold can't be above 100");
        if (in.deviceId() != null && !devices.existsById(in.deviceId())) throw ApiException.notFound("Device");
    }

    private static Integer port(RuleInput in) {
        return in.type() == RuleType.PORT_CLOSED ? in.port() : null;
    }

    private static String iface(RuleInput in) {
        return in.type().usesInterface() && in.interfaceName() != null && !in.interfaceName().isBlank() ? in.interfaceName().trim() : null;
    }

    public List<WindowDto> windows() {
        return jdbc.query("""
                select m.id, m.device_id, d.name, m.starts_at, m.ends_at, m.reason, m.created_by
                from maintenance_window m join device d on d.id = m.device_id
                where m.ends_at > now() - interval '7 days' order by m.starts_at desc limit 200""",
                (rs, n) -> new WindowDto(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7)));
    }

    @Transactional
    public UUID createWindow(UUID deviceId, Instant start, Instant end, String reason) {
        if (!devices.existsById(deviceId)) throw ApiException.notFound("Device");
        if (!end.isAfter(start)) throw ApiException.badRequest("The end must be after the start");
        if (!end.isAfter(clock.instant())) throw ApiException.badRequest("The window must end in the future");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into maintenance_window (id, device_id, starts_at, ends_at, reason, created_by) values (?, ?, ?, ?, ?, ?)",
                id, deviceId, Timestamp.from(start), Timestamp.from(end), reason == null || reason.isBlank() ? null : reason.trim(), CurrentUser.name());
        audit.record("MAINTENANCE_CREATE", "device", deviceId, "Maintenance " + start + " to " + end);
        return id;
    }

    @Transactional
    public void deleteWindow(UUID id) {
        if (jdbc.update("delete from maintenance_window where id = ?", id) == 0) throw ApiException.notFound("Maintenance window");
        audit.record("MAINTENANCE_DELETE", "maintenance_window", id, "Maintenance window removed");
    }
}
