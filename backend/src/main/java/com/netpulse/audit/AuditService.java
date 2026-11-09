package com.netpulse.audit;

import com.netpulse.auth.JwtService;
import com.netpulse.common.web.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who changed what, and when. Writes join the caller's transaction, so a change and its audit entry
 * commit or roll back together. Summaries are built by callers and must never include a secret.
 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Entry(long id, String actor, String action, String entityType, String entityId, String summary,
                        String clientIp, Instant occurredAt) { }

    public void record(String action, String entityType, Object entityId, String summary) {
        UUID actorId = CurrentUser.get().map(JwtService.Principal::userId).orElse(null);
        String trimmed = summary.length() > 500 ? summary.substring(0, 500) : summary;
        jdbc.update("""
                insert into audit_log (actor_id, actor_name, action, entity_type, entity_id, summary, client_ip, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""",
                actorId, CurrentUser.name(), action, entityType, entityId == null ? null : entityId.toString(), trimmed,
                clientIp(), Timestamp.from(clock.instant()));
    }

    public Page<Entry> search(String actor, String action, Instant from, Instant to, Pageable pageable) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        if (actor != null && !actor.isBlank()) { where.append(" and lower(actor_name) = lower(?)"); args.add(actor.trim()); }
        if (action != null && !action.isBlank()) { where.append(" and action = ?"); args.add(action.trim()); }
        if (from != null) { where.append(" and occurred_at >= ?"); args.add(Timestamp.from(from)); }
        if (to != null) { where.append(" and occurred_at < ?"); args.add(Timestamp.from(to)); }
        Long total = jdbc.queryForObject("select count(*) from audit_log" + where, Long.class, args.toArray());
        args.add(pageable.getPageSize());
        args.add(pageable.getOffset());
        List<Entry> rows = jdbc.query("select id, actor_name, action, entity_type, entity_id, summary, client_ip, occurred_at from audit_log"
                + where + " order by occurred_at desc, id desc limit ? offset ?",
                (rs, n) -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()), args.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static String clientIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest().getRemoteAddr() : null;
    }
}
