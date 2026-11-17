package com.netpulse.notification;

import com.netpulse.alert.AlertTypes.Severity;
import com.netpulse.alert.AlertTypes.State;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Queues a message for each enabled channel that wants this severity, inside the caller's transaction. */
@Component
public class NotificationOutbox {
    private final JdbcTemplate jdbc;

    public NotificationOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID alertId, Severity severity, State event, String message, Instant now) {
        String subject = "[NetPulse] " + severity + " " + event + ": " + message;
        Timestamp at = Timestamp.from(now);
        // Severity is declared most severe first: a channel receives everything at or above its minimum.
        for (var channel : jdbc.query("select id, min_severity from notification_channel where enabled",
                (rs, n) -> new Object[]{rs.getObject(1, UUID.class), Severity.valueOf(rs.getString(2))})) {
            if (severity.ordinal() > ((Severity) channel[1]).ordinal()) continue;
            jdbc.update("""
                    insert into notification_delivery (channel_id, alert_id, event, subject, body, status, next_attempt_at, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', ?, ?)""",
                    channel[0], alertId, event.name(), truncate(subject, 300), truncate(message, 1000), at, at);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
