package com.netpulse.notification;

import com.netpulse.audit.AuditService;
import com.netpulse.alert.AlertTypes.Severity;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.crypto.CredentialCipher;
import com.netpulse.common.error.ApiException;
import com.netpulse.common.net.TargetValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Channel management and the delivery worker. Sends happen outside transactions so a slow SMTP server holds no database connection. */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,]+@[^@\\s,]+$");
    private static final int BATCH = 20;
    /** While a worker sends, the row is hidden from other runs for this long. If the process dies it simply becomes due again. */
    private static final Duration LEASE = Duration.ofMinutes(2);

    public record ChannelInput(String name, String type, boolean enabled, Severity minSeverity, String recipients, String webhookFormat, String webhookUrl, String telegramChatId) { }

    public record ChannelDto(UUID id, String name, String type, boolean enabled, Severity minSeverity, String recipients, String webhookFormat, String webhookHost, String telegramChatId) { }

    public record DeliveryDto(long id, String channelName, String event, String subject, String status, int attempts, String lastError, Instant createdAt, Instant sentAt) { }

    private record Claimed(long id, int attempts, String subject, String body, ChannelSender.Channel channel) { }

    private final JdbcTemplate jdbc;
    private final ChannelSender sender;
    private final CredentialCipher cipher;
    private final TargetValidator validator;
    private final AuditService audit;
    private final NetPulseProperties.Notifications cfg;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, ChannelSender sender, CredentialCipher cipher, TargetValidator validator, AuditService audit, NetPulseProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.cipher = cipher;
        this.validator = validator;
        this.audit = audit;
        this.cfg = props.notifications();
        this.clock = clock;
    }

    /** Exponential: initial, 2x, 4x ... capped at one hour, so a recovering endpoint isn't hammered. */
    public static Duration backoff(Duration initial, int attempt) {
        long factor = 1L << Math.min(attempt - 1, 20);
        Duration d = initial.multipliedBy(factor);
        Duration cap = Duration.ofHours(1);
        return d.compareTo(cap) > 0 ? cap : d;
    }

    /** @return number of messages attempted */
    public int deliverDue() {
        List<Claimed> batch = claim();
        for (Claimed c : batch) deliver(c);
        return batch.size();
    }

    /** SKIP LOCKED means overlapping runs take different rows, so a message is never sent twice at once. */
    private List<Claimed> claim() {
        Instant now = clock.instant();
        return jdbc.query("""
                with due as (
                    select id from notification_delivery where status = 'PENDING' and next_attempt_at <= ?
                    order by id limit ? for update skip locked),
                updated as (
                    update notification_delivery n set attempts = n.attempts + 1, next_attempt_at = ?
                    from due where n.id = due.id returning n.id, n.attempts, n.subject, n.body, n.channel_id)
                select u.id, u.attempts, u.subject, u.body, c.id, c.type, c.recipients, c.webhook_format, c.webhook_url_enc, c.telegram_chat_id
                from updated u join notification_channel c on c.id = u.channel_id""",
                (rs, n) -> new Claimed(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        new ChannelSender.Channel(rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10))),
                Timestamp.from(now), BATCH, Timestamp.from(now.plus(LEASE)));
    }

    private void deliver(Claimed c) {
        try {
            sender.send(c.channel(), c.subject(), c.body());
            jdbc.update("update notification_delivery set status = 'SENT', sent_at = ?, last_error = null where id = ?", Timestamp.from(clock.instant()), c.id());
        } catch (ChannelSender.SendFailure e) {
            boolean giveUp = c.attempts() >= cfg.maxAttempts();
            jdbc.update("update notification_delivery set status = ?, last_error = ?, next_attempt_at = ? where id = ?",
                    giveUp ? "FAILED" : "PENDING", e.getMessage(), Timestamp.from(clock.instant().plus(backoff(cfg.initialBackoff(), c.attempts()))), c.id());
            log.warn("Notification {} attempt {} failed: {}", c.id(), c.attempts(), e.getMessage());
        }
    }

    private static final RowMapper<ChannelDto> CHANNEL = (rs, n) -> new ChannelDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4),
            Severity.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));

    public List<ChannelDto> channels() {
        return jdbc.query("select id, name, type, enabled, min_severity, recipients, webhook_format, webhook_host, telegram_chat_id from notification_channel order by lower(name)", CHANNEL);
    }

    public List<DeliveryDto> recentDeliveries() {
        return jdbc.query("""
                select d.id, c.name, d.event, d.subject, d.status, d.attempts, d.last_error, d.created_at, d.sent_at
                from notification_delivery d join notification_channel c on c.id = d.channel_id order by d.id desc limit 50""",
                (rs, n) -> {
                    Timestamp sent = rs.getTimestamp(9);
                    return new DeliveryDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7),
                            rs.getTimestamp(8).toInstant(), sent == null ? null : sent.toInstant());
                });
    }

    @Transactional
    public UUID create(ChannelInput in) {
        UUID id = UUID.randomUUID();
        Fields f = validate(id, in, null);
        jdbc.update("""
                insert into notification_channel (id, name, type, enabled, min_severity, recipients, webhook_format, webhook_url_enc, webhook_host, telegram_chat_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, in.name().trim(), in.type(), in.enabled(), in.minSeverity().name(), f.recipients(), f.format(), f.encryptedUrl(), f.host(), f.chatId());
        audit.record("CHANNEL_CREATE", "notification_channel", id, "Created " + in.type() + " channel '" + in.name().trim() + "'");
        return id;
    }

    @Transactional
    public void update(UUID id, ChannelInput in) {
        Stored existing = jdbc.query("select webhook_url_enc, webhook_host from notification_channel where id = ?",
                (rs, n) -> new Stored(rs.getString(1), rs.getString(2)), id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Channel"));
        Fields f = validate(id, in, existing);
        jdbc.update("""
                update notification_channel set name = ?, type = ?, enabled = ?, min_severity = ?, recipients = ?, webhook_format = ?,
                    webhook_url_enc = ?, webhook_host = ?, telegram_chat_id = ? where id = ?""",
                in.name().trim(), in.type(), in.enabled(), in.minSeverity().name(), f.recipients(), f.format(), f.encryptedUrl(), f.host(), f.chatId(), id);
        audit.record("CHANNEL_UPDATE", "notification_channel", id, "Updated channel '" + in.name().trim() + "'");
    }

    @Transactional
    public void delete(UUID id) {
        if (jdbc.update("delete from notification_channel where id = ?", id) == 0) throw ApiException.notFound("Channel");
        audit.record("CHANNEL_DELETE", "notification_channel", id, "Deleted notification channel");
    }

    /** Sends right now and reports the real outcome to the person who clicked "Send test". */
    public void test(UUID id) {
        ChannelSender.Channel c = jdbc.query("select id, type, recipients, webhook_format, webhook_url_enc, telegram_chat_id from notification_channel where id = ?",
                (rs, n) -> new ChannelSender.Channel(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Channel"));
        try {
            sender.send(c, "[NetPulse] Test notification", "This is a test from NetPulse. Your channel works.");
        } catch (ChannelSender.SendFailure e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Delivery failed", e.getMessage());
        }
    }

    private record Stored(String encryptedUrl, String host) { }
    private record Fields(String recipients, String format, String encryptedUrl, String host, String chatId) { }

    private Fields validate(UUID id, ChannelInput in, Stored existing) {
        if ("EMAIL".equals(in.type())) {
            if (in.recipients() == null || in.recipients().isBlank()) throw ApiException.badRequest("Add at least one recipient");
            List<String> list = java.util.Arrays.stream(in.recipients().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (list.isEmpty() || !list.stream().allMatch(s -> EMAIL.matcher(s).matches())) throw ApiException.badRequest("Enter valid email addresses, separated by commas");
            return new Fields(String.join(",", list), null, null, null, null);
        }
        if (!"WEBHOOK".equals(in.type())) throw ApiException.badRequest("Type must be EMAIL or WEBHOOK");
        if (in.webhookFormat() == null) throw ApiException.badRequest("Choose a webhook format");
        if ("TELEGRAM".equals(in.webhookFormat()) && (in.telegramChatId() == null || in.telegramChatId().isBlank())) throw ApiException.badRequest("Telegram needs a chat id");
        String chatId = in.telegramChatId() == null || in.telegramChatId().isBlank() ? null : in.telegramChatId().trim();
        if (in.webhookUrl() == null || in.webhookUrl().isBlank()) {
            if (existing == null || existing.encryptedUrl() == null) throw ApiException.badRequest("Enter the webhook URL");
            return new Fields(null, in.webhookFormat(), existing.encryptedUrl(), existing.host(), chatId);   // blank keeps the stored URL
        }
        String host = webhookHost(in.webhookUrl().trim());
        validator.validateHost(host);
        return new Fields(null, in.webhookFormat(), cipher.encrypt(in.webhookUrl().trim(), id.toString()), host, chatId);
    }

    private String webhookHost(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Enter a valid URL");
        }
        // Webhook URLs usually embed a secret, so plain http is only for local testing.
        boolean allowed = Objects.equals(uri.getScheme(), "https") || (validator.allowsLocalTargets() && Objects.equals(uri.getScheme(), "http"));
        if (!allowed) throw ApiException.badRequest("The URL must start with https://");
        if (uri.getHost() == null) throw ApiException.badRequest("Enter a valid URL");
        return uri.getHost();
    }
}
