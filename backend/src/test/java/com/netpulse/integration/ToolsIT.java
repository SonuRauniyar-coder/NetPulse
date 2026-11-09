package com.netpulse.integration;

import com.jayway.jsonpath.JsonPath;
import com.netpulse.settings.SettingsService;
import com.netpulse.snmp.SnmpSnapshot;
import com.netpulse.snmp.SnmpStore;
import com.netpulse.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ToolsIT extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired SnmpStore snmp;
    @Autowired SettingsService settings;
    String admin;
    UUID device;

    @BeforeEach void setUp() {
        jdbc.update("delete from device");
        admin = tokenFor(Role.ADMIN);
        device = UUID.randomUUID();
        jdbc.update("insert into device (id, name, host, type, check_interval_seconds) values (?, 'rtr', '127.0.0.1', 'ROUTER', 60)", device);
    }

    @Test void subnetApi() throws Exception {
        mvc.perform(get("/api/v1/subnet/calculate").param("cidr", "192.168.10.77/26").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.network").value("192.168.10.64")).andExpect(jsonPath("$.usableHosts").value(62));
        mvc.perform(get("/api/v1/subnet/calculate").param("cidr", "nonsense").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/subnet/vlsm").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"network\":\"10.0.0.0/24\",\"segments\":[{\"name\":\"A\",\"hosts\":10},{\"name\":\"B\",\"hosts\":100}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allocations[0].name").value("B"));
    }

    @Test void realLoopbackScanIsAuditedAndFindsHosts() throws Exception {
        String res = mvc.perform(post("/api/v1/discovery/scans").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"cidr\":\"127.0.0.0/30\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");
        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> !"RUNNING".equals(jdbc.queryForObject("select status from discovery_scan where id = ?::uuid", String.class, id)));
        mvc.perform(get("/api/v1/discovery/scans/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.scan.status").value("COMPLETED"))
                .andExpect(jsonPath("$.hosts[?(@.ip == '127.0.0.1')].monitored").value(true));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'DISCOVERY_SCAN' and entity_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test void publicAndOversizedScansAreRefused() throws Exception {
        mvc.perform(post("/api/v1/discovery/scans").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"cidr\":\"8.8.8.0/24\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/discovery/scans").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"cidr\":\"10.0.0.0/20\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void realLoopbackTraceroute() throws Exception {
        String res = mvc.perform(post("/api/v1/traceroute").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"127.0.0.1\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");
        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            String body = mvc.perform(get("/api/v1/traceroute/" + id).header("Authorization", admin)).andReturn().getResponse().getContentAsString();
            return !"RUNNING".equals(JsonPath.read(body, "$.status"));
        });
        mvc.perform(get("/api/v1/traceroute/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.reachedTarget").value(true))
                .andExpect(jsonPath("$.hops[0].ip").value("127.0.0.1"));
    }

    @Test void tracerouteRejectsBadTargets() throws Exception {
        mvc.perform(post("/api/v1/traceroute").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"8.8.8.8; rm -rf /\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/traceroute").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"224.0.0.1\"}"))
                .andExpect(status().isBadRequest());
    }

    private void saveV2() throws Exception {
        mvc.perform(put("/api/v1/devices/" + device + "/snmp").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"version\":\"V2C\",\"community\":\"very-secret-community\"}"))
                .andExpect(status().isOk());
    }

    @Test void snmpSecretsAreEncryptedAndNeverReturned() throws Exception {
        saveV2();
        assertThat(jdbc.queryForObject("select credentials from snmp_config where device_id = ?", String.class, device)).doesNotContain("very-secret-community");
        mvc.perform(get("/api/v1/devices/" + device + "/snmp").header("Authorization", tokenFor(Role.VIEWER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.config.version").value("V2C"))
                .andExpect(content().string(not(containsString("very-secret-community"))))
                .andExpect(content().string(not(containsString("credentials"))));
        String audit = jdbc.queryForObject("select summary from audit_log where action = 'SNMP_CONFIG_SAVE' order by id desc limit 1", String.class);
        assertThat(audit).doesNotContain("very-secret-community");
    }

    @Test void blankSecretKeepsTheStoredOne() throws Exception {
        saveV2();
        String before = jdbc.queryForObject("select credentials from snmp_config where device_id = ?", String.class, device);
        mvc.perform(put("/api/v1/devices/" + device + "/snmp").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"version\":\"V2C\",\"port\":1161}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select credentials from snmp_config where device_id = ?", String.class, device)).isEqualTo(before);
        mvc.perform(put("/api/v1/devices/" + device + "/snmp").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"version\":\"V3\"}")).andExpect(status().isBadRequest());
    }

    private static SnmpSnapshot snap(long ticks, long in, long out, int bits) {
        return new SnmpSnapshot("rtr", "desc", "loc", ticks, List.of(new SnmpSnapshot.Interface(1, "Gi0/0", 1, 1, 1_000_000_000L,
                BigInteger.valueOf(in), BigInteger.valueOf(out), bits, BigInteger.ZERO, BigInteger.ZERO)));
    }

    @Test void ratesComeFromDeltasAndARebootIsNotASpike() throws Exception {
        saveV2();
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        snmp.recordSuccess(device, snap(100_000, 1_000_000, 2_000_000, 64), t);
        assertThat(jdbc.queryForObject("select count(*) from snmp_sample where device_id = ?", Integer.class, device)).isZero();

        // 10 seconds later: 1.25 MB in = 1 Mbit/s; 12.5 MB out = 10 Mbit/s = 1% of 1 Gbit/s.
        snmp.recordSuccess(device, snap(101_000, 2_250_000, 14_500_000, 64), t.plusSeconds(10));
        assertThat(jdbc.queryForObject("select in_bps from snmp_interface where device_id = ?", Double.class, device)).isEqualTo(1_000_000.0);
        assertThat(jdbc.queryForObject("select utilization_pct from snmp_interface where device_id = ?", Double.class, device)).isEqualTo(1.0);

        // Reboot: uptime and counters both restart. The small counters must not become a huge rate.
        snmp.recordSuccess(device, snap(500, 100, 100, 64), t.plusSeconds(20));
        assertThat(jdbc.queryForObject("select in_bps from snmp_interface where device_id = ?", Double.class, device)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from snmp_sample where device_id = ?", Integer.class, device)).isEqualTo(1);

        mvc.perform(get("/api/v1/devices/" + device + "/snmp").header("Authorization", admin))
                .andExpect(jsonPath("$.interfaces[0].descr").value("Gi0/0")).andExpect(jsonPath("$.interfaces[0].samples.length()").value(1));
    }

    private void cycle(Instant at, String status) {
        jdbc.update("insert into device_check (device_id, checked_at, verdict, status, duration_ms) values (?, ?, ?, ?, 10)",
                device, Timestamp.from(at), "DOWN".equals(status) ? "FAIL" : status, status);
    }

    private ResultReader report(Instant from, Instant to) throws Exception {
        return new ResultReader(mvc.perform(get("/api/v1/reports/uptime").header("Authorization", admin)
                .param("from", from.toString()).param("to", to.toString()).param("slaTarget", "90")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private record ResultReader(String json) {
        <T> T at(String path) { return JsonPath.read(json, path); }
    }

    @Test void uptimeExcludesMaintenanceAndUnknownTime() throws Exception {
        Instant base = Instant.now().minus(java.time.Duration.ofHours(3)).truncatedTo(ChronoUnit.HOURS);
        for (int i = 0; i < 9; i++) cycle(base.plusSeconds(60L * i), "UP");
        cycle(base.plusSeconds(600), "DOWN");
        cycle(base.plusSeconds(660), "UNKNOWN");
        // A DOWN cycle inside a maintenance window must not count against the SLA.
        cycle(base.plusSeconds(1800), "DOWN");
        jdbc.update("insert into maintenance_window (id, device_id, starts_at, ends_at, created_by) values (gen_random_uuid(), ?, ?, ?, 'test')",
                device, Timestamp.from(base.plusSeconds(1700)), Timestamp.from(base.plusSeconds(1900)));

        ResultReader r = report(base, base.plus(java.time.Duration.ofHours(2)));
        assertThat((int) r.at("$.devices[0].observedChecks")).isEqualTo(10);
        assertThat((double) r.at("$.devices[0].uptimePct")).isEqualTo(90.0);
        assertThat((boolean) r.at("$.devices[0].slaMet")).isTrue();
        assertThat((int) r.at("$.devices[0].downtimeSeconds")).isEqualTo(60);
    }

    @Test void olderPeriodsUseRollupsAndDropHoursTouchingMaintenance() throws Exception {
        Instant old = Instant.now().minus(java.time.Duration.ofDays(30)).truncatedTo(ChronoUnit.HOURS);
        rollup(old, 10, 0);
        rollup(old.plus(java.time.Duration.ofHours(1)), 8, 2);
        rollup(old.plus(java.time.Duration.ofHours(2)), 0, 10);
        jdbc.update("insert into maintenance_window (id, device_id, starts_at, ends_at, created_by) values (gen_random_uuid(), ?, ?, ?, 'test')",
                device, Timestamp.from(old.plus(java.time.Duration.ofMinutes(130))), Timestamp.from(old.plus(java.time.Duration.ofMinutes(140))));
        ResultReader r = report(old, old.plus(java.time.Duration.ofHours(3)));
        assertThat((int) r.at("$.devices[0].observedChecks")).isEqualTo(20);
        assertThat((double) r.at("$.devices[0].uptimePct")).isEqualTo(90.0);
    }

    private void rollup(Instant bucket, int up, int down) {
        jdbc.update("insert into rollup_hourly (device_id, bucket, checks, up, degraded, down) values (?, ?, ?, ?, 0, ?)", device, Timestamp.from(bucket), up + down, up, down);
    }

    @Test void deviceWithNoObservationsReportsNoUptimeRatherThanZero() throws Exception {
        ResultReader r = report(Instant.now().minusSeconds(3600), Instant.now());
        assertThat((int) r.at("$.devices[0].observedChecks")).isZero();
        assertThat(r.json()).doesNotContain("uptimePct");
    }

    @Test void eventLogFiltersAndCsvNeutralizesFormulas() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("update device set name = '=cmd|calc' where id = ?", device);
        jdbc.update("insert into status_event (device_id, from_status, to_status, reason, occurred_at) values (?, 'UP', 'DOWN', 'a, b', ?)", device, Timestamp.from(now));
        jdbc.update("insert into status_event (device_id, from_status, to_status, reason, occurred_at) values (?, 'DOWN', 'UP', 'ok', ?)", device, Timestamp.from(now.plusSeconds(5)));
        mvc.perform(get("/api/v1/events").param("status", "DOWN").header("Authorization", admin)).andExpect(jsonPath("$.content.length()").value(1));
        String csv = mvc.perform(get("/api/v1/events/export.csv").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("netpulse-events.csv"))).andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("occurred_at,device").contains("'=cmd|calc").contains("\"a, b\"");
    }
}
