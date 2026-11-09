package com.netpulse.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.crypto.CredentialCipher;
import com.netpulse.common.net.InvalidTargetException;
import com.netpulse.common.net.TargetValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;

/**
 * Delivers one message. Failures are reported as {@link SendFailure} with text that is safe to store and display:
 * a webhook URL is a credential, so neither it nor an exception that might embed it is ever passed on.
 */
@Component
public class ChannelSender {
    public static class SendFailure extends Exception {
        public SendFailure(String message) { super(message); }
    }

    public record Channel(java.util.UUID id, String type, String recipients, String format, String encryptedUrl, String chatId) { }

    private final ObjectProvider<JavaMailSender> mail;
    private final String mailHost;
    private final CredentialCipher cipher;
    private final TargetValidator validator;
    private final ObjectMapper mapper;
    private final NetPulseProperties.Notifications cfg;
    private final HttpClient http;

    public ChannelSender(ObjectProvider<JavaMailSender> mail, @Value("${spring.mail.host:}") String mailHost, CredentialCipher cipher,
                         TargetValidator validator, ObjectMapper mapper, NetPulseProperties props) {
        this.mail = mail;
        this.mailHost = mailHost;
        this.cipher = cipher;
        this.validator = validator;
        this.mapper = mapper;
        this.cfg = props.notifications();
        // No redirects: an allowed URL must not be able to bounce the request to an internal address.
        this.http = HttpClient.newBuilder().connectTimeout(cfg.webhookTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void send(Channel c, String subject, String body) throws SendFailure {
        if ("EMAIL".equals(c.type())) sendEmail(c, subject, body);
        else sendWebhook(c, subject, body);
    }

    private void sendEmail(Channel c, String subject, String body) throws SendFailure {
        JavaMailSender sender = mail.getIfAvailable();
        if (mailHost.isBlank() || sender == null) throw new SendFailure("SMTP isn't configured on the server (set SMTP_HOST)");
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(cfg.mailFrom());
        m.setTo(Arrays.stream(c.recipients().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new));
        m.setSubject(subject);
        m.setText(body);
        try {
            sender.send(m);
        } catch (MailException e) {
            throw new SendFailure("SMTP delivery failed: " + e.getClass().getSimpleName());
        }
    }

    private void sendWebhook(Channel c, String subject, String body) throws SendFailure {
        URI uri;
        try {
            uri = URI.create(cipher.decrypt(c.encryptedUrl(), c.id().toString()));
            validator.resolve(uri.getHost());   // refuses loopback, link-local and the cloud metadata address
        } catch (UnknownHostException e) {
            throw new SendFailure("Webhook host could not be resolved");
        } catch (InvalidTargetException e) {
            throw new SendFailure("Webhook host is not an allowed destination");
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new SendFailure("Stored webhook URL could not be read");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(cfg.webhookTimeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(c, subject, body))).build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) throw new SendFailure("Webhook returned HTTP " + status);
        } catch (IOException e) {
            throw new SendFailure("Webhook request failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendFailure("Interrupted while sending");
        }
    }

    /** Each service expects its own field name, so "Slack-compatible" isn't enough on its own. */
    String payload(Channel c, String subject, String body) throws SendFailure {
        String text = subject.contains(body) ? subject : subject + "\n" + body;   // an alert's subject already carries its message
        Map<String, Object> json = switch (c.format()) {
            case "SLACK" -> Map.of("text", text);
            case "DISCORD" -> Map.of("content", text.length() > 2000 ? text.substring(0, 2000) : text);   // Discord's limit
            case "TELEGRAM" -> Map.of("chat_id", c.chatId() == null ? "" : c.chatId(), "text", text);
            default -> Map.of("subject", subject, "body", body);
        };
        try {
            return mapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new SendFailure("Could not build the message");
        }
    }
}
