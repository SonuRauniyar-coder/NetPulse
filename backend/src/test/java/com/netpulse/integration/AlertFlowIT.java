package com.netpulse.integration;

import com.jayway.jsonpath.JsonPath;
import com.netpulse.alert.AlertEngine;
import com.netpulse.alert.RuleEvaluator.CheckFacts;
import com.netpulse.device.DeviceStatus;
import com.netpulse.notification.NotificationService;
import com.netpulse.user.Role;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlertFlowIT extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired AlertEngine engine;
    @Autowired NotificationService notifications;

    UUID device;
    String admin;
    HttpServer webhook;
    final List<String> received = new CopyOnWriteArrayList<>();
    final AtomicInteger failuresLeft = new AtomicInteger();

    @BeforeEach void setUp() throws Exception {
        jdbc.update("delete from notification_channel");
        jdbc.update("delete from alert_rule");
        jdbc.update("delete from device");
        admin = tokenFor(Role.ADMIN);
        device = UUID.randomUUID();
        jdbc.update("insert into device (id, name, host, type, check_interval_seconds) values (?, 'sw1', '192.0.2.1', 'SWITCH', 60)", device);
        webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext("/hook/secret-token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes());
            int code = failuresLeft.getAndDecrement() > 0 ? 500 : 200;
            if (code == 200) received.add(body);
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        webhook.start();
    }

    @AfterEach void stop() {
        webhook.stop(0);
    }

    private String rule(String type, String extra, int duration) throws Exception {
        String res = mvc.perform(post("/api/v1/alert-rules").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"r-%s\",\"type\":\"%s\",\"severity\":\"CRITICAL\",\"durationSeconds\":%d,\"enabled\":true%s}".formatted(UUID.randomUUID(), type, duration, extra)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.id");
    }

    private void channel() throws Exception {
        mvc.perform(post("/api/v1/notifications/channels").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hook\",\"type\":\"WEBHOOK\",\"enabled\":true,\"minSeverity\":\"WARNING\",\"webhookFormat\":\"SLACK\",\"webhookUrl\":\"http://127.0.0.1:%d/hook/secret-token\"}".formatted(webhook.getAddress().getPort())))
                .andExpect(status().isCreated());
    }

    private void observe(DeviceStatus status) {
        engine.onCheck(device, new CheckFacts("sw1", status, status == DeviceStatus.UP ? 2.0 : null, status == DeviceStatus.UP ? 0.0 : 100.0, Map.of()));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String state() {
        return jdbc.queryForObject("select state from alert order by triggered_at desc limit 1", String.class);
    }

    private UUID alertId() {
        return jdbc.queryForObject("select id from alert order by triggered_at desc limit 1", UUID.class);
    }

    @Test void fullLifecycleNotifiesOnlyOnStateChanges() throws Exception {
        rule("DEVICE_DOWN", "", 0);
        channel();

        observe(DeviceStatus.DOWN);
        observe(DeviceStatus.DOWN);   // still down: no second alert, no second notification
        assertThat(count("select count(*) from alert")).isEqualTo(1);
        assertThat(state()).isEqualTo("TRIGGERED");
        assertThat(count("select count(*) from notification_delivery")).isEqualTo(1);

        mvc.perform(post("/api/v1/alerts/" + alertId() + "/acknowledge").header("Authorization", tokenFor(Role.OPERATOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACKNOWLEDGED"));
        mvc.perform(post("/api/v1/alerts/" + alertId() + "/acknowledge").header("Authorization", admin)).andExpect(status().isConflict());

        observe(DeviceStatus.UP);
        assertThat(state()).isEqualTo("RESOLVED");
        assertThat(count("select count(*) from notification_delivery")).isEqualTo(3);

        mvc.perform(get("/api/v1/alerts/summary").header("Authorization", admin)).andExpect(jsonPath("$.active").value(0));

        notifications.deliverDue();
        assertThat(received).hasSize(3);
        assertThat(received.getFirst()).contains("\"text\"").contains("sw1 is down");
        assertThat(count("select count(*) from notification_delivery where status = 'SENT'")).isEqualTo(3);
    }

    @Test void durationDelaysTheAlertAndARecoveryCancelsIt() throws Exception {
        String ruleId = rule("DEVICE_DOWN", "", 300);
        observe(DeviceStatus.DOWN);
        assertThat(count("select count(*) from alert")).isZero();
        assertThat(count("select count(*) from alert_pending")).isEqualTo(1);

        observe(DeviceStatus.UP);
        assertThat(count("select count(*) from alert_pending")).isZero();

        observe(DeviceStatus.DOWN);
        jdbc.update("update alert_pending set breaching_since = ? where rule_id = ?::uuid", Timestamp.from(Instant.now().minusSeconds(301)), ruleId);
        observe(DeviceStatus.DOWN);
        assertThat(count("select count(*) from alert")).isEqualTo(1);
    }

    @Test void maintenanceWindowMutes() throws Exception {
        rule("DEVICE_DOWN", "", 0);
        channel();
        mvc.perform(post("/api/v1/maintenance-windows").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(device, Instant.now().minusSeconds(60), Instant.now().plusSeconds(600))))
                .andExpect(status().isCreated());
        observe(DeviceStatus.DOWN);
        assertThat(count("select count(*) from alert")).isZero();
        assertThat(count("select count(*) from notification_delivery")).isZero();
    }

    @Test void pausingADeviceResolvesItsAlerts() throws Exception {
        rule("DEVICE_DOWN", "", 0);
        jdbc.update("update device set next_check_at = now() where id = ?", device);
        observe(DeviceStatus.DOWN);
        mvc.perform(post("/api/v1/devices/" + device + "/pause").header("Authorization", admin)).andExpect(status().isOk());
        assertThat(state()).isEqualTo("RESOLVED");
    }

    @Test void deletingARuleResolvesItsAlertsAndKeepsThem() throws Exception {
        String id = rule("DEVICE_DOWN", "", 0);
        observe(DeviceStatus.DOWN);
        mvc.perform(delete("/api/v1/alert-rules/" + id).header("Authorization", admin)).andExpect(status().isNoContent());
        assertThat(count("select count(*) from alert where state = 'RESOLVED' and rule_id is null")).isEqualTo(1);
    }

    @Test void failedDeliveriesRetryWithBackoffThenSucceed() throws Exception {
        rule("DEVICE_DOWN", "", 0);
        channel();
        failuresLeft.set(1);
        observe(DeviceStatus.DOWN);

        notifications.deliverDue();
        assertThat(received).isEmpty();
        assertThat(jdbc.queryForObject("select status from notification_delivery", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select last_error from notification_delivery", String.class)).isEqualTo("Webhook returned HTTP 500");
        assertThat(notifications.deliverDue()).as("not due again until the backoff passes").isZero();

        jdbc.update("update notification_delivery set next_attempt_at = now()");
        notifications.deliverDue();
        assertThat(received).hasSize(1);
        assertThat(jdbc.queryForObject("select attempts from notification_delivery", Integer.class)).isEqualTo(2);
    }

    @Test void givesUpAfterMaxAttempts() throws Exception {
        rule("DEVICE_DOWN", "", 0);
        channel();
        failuresLeft.set(100);
        observe(DeviceStatus.DOWN);
        for (int i = 0; i < 6; i++) {
            jdbc.update("update notification_delivery set next_attempt_at = now() where status = 'PENDING'");
            notifications.deliverDue();
        }
        assertThat(jdbc.queryForObject("select status from notification_delivery", String.class)).isEqualTo("FAILED");
    }

    @Test void channelFiltersBySeverity() throws Exception {
        mvc.perform(post("/api/v1/notifications/channels").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"crit\",\"type\":\"EMAIL\",\"enabled\":true,\"minSeverity\":\"CRITICAL\",\"recipients\":\"a@example.com\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/alert-rules").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"info\",\"type\":\"DEVICE_DOWN\",\"severity\":\"INFO\",\"durationSeconds\":0,\"enabled\":true}")).andExpect(status().isCreated());
        observe(DeviceStatus.DOWN);
        assertThat(count("select count(*) from alert")).isEqualTo(1);
        assertThat(count("select count(*) from notification_delivery")).isZero();
    }

    @Test void webhookUrlIsEncryptedAndNeverReturned() throws Exception {
        channel();
        assertThat(jdbc.queryForObject("select webhook_url_enc from notification_channel", String.class)).doesNotContain("secret-token");
        mvc.perform(get("/api/v1/notifications/channels").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].webhookHost").value("127.0.0.1"))
                .andExpect(content().string(not(containsString("secret-token"))))
                .andExpect(content().string(not(containsString("webhookUrl"))));
        String auditJson = mvc.perform(get("/api/v1/audit?action=CHANNEL_CREATE").header("Authorization", admin)).andReturn().getResponse().getContentAsString();
        assertThat(auditJson).doesNotContain("secret-token");
    }

    @Test void testButtonReportsTheRealResult() throws Exception {
        channel();
        String id = jdbc.queryForObject("select id::text from notification_channel", String.class);
        mvc.perform(post("/api/v1/notifications/channels/" + id + "/test").header("Authorization", admin)).andExpect(status().isNoContent());
        assertThat(received).hasSize(1);
        failuresLeft.set(1);
        mvc.perform(post("/api/v1/notifications/channels/" + id + "/test").header("Authorization", admin))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.detail").value("Webhook returned HTTP 500"));
    }

    @Test void emailWithoutSmtpFailsClearly() throws Exception {
        mvc.perform(post("/api/v1/notifications/channels").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mail\",\"type\":\"EMAIL\",\"enabled\":true,\"minSeverity\":\"INFO\",\"recipients\":\"a@example.com\"}"))
                .andExpect(status().isCreated());
        String id = jdbc.queryForObject("select id::text from notification_channel", String.class);
        mvc.perform(post("/api/v1/notifications/channels/" + id + "/test").header("Authorization", admin))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.detail").value(containsString("SMTP")));
    }

    @Test void ruleValidation() throws Exception {
        mvc.perform(post("/api/v1/alert-rules").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"RTT_ABOVE\",\"severity\":\"INFO\",\"durationSeconds\":0,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alert-rules").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"LOSS_ABOVE\",\"severity\":\"INFO\",\"threshold\":120,\"durationSeconds\":0,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }
}
