package com.netpulse.integration;

import com.netpulse.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Who may do what. Each case targets a role boundary from the requirements. */
class AccessControlIT extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    String admin;
    String operator;
    String viewer;

    @BeforeEach void setUp() {
        jdbc.update("delete from device");
        admin = tokenFor(Role.ADMIN);
        operator = tokenFor(Role.OPERATOR);
        viewer = tokenFor(Role.VIEWER);
    }

    private int status(MockHttpServletRequestBuilder req, String token) throws Exception {
        return mvc.perform(req.header("Authorization", token).contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.content(body);
    }

    @Test void viewerIsReadOnly() throws Exception {
        assertThat(status(get("/api/v1/devices"), viewer)).isEqualTo(200);
        assertThat(status(get("/api/v1/alerts"), viewer)).isEqualTo(200);
        assertThat(status(get("/api/v1/events"), viewer)).isEqualTo(200);
        assertThat(status(get("/api/v1/subnet/calculate?cidr=10.0.0.0/24"), viewer)).isEqualTo(200);
        assertThat(status(json(post("/api/v1/devices"), "{\"name\":\"x\",\"host\":\"8.8.8.8\",\"type\":\"SERVER\"}"), viewer)).isEqualTo(403);
        assertThat(status(post("/api/v1/alerts/" + UUID.randomUUID() + "/acknowledge"), viewer)).isEqualTo(403);
        assertThat(status(json(post("/api/v1/discovery/scans"), "{\"cidr\":\"127.0.0.0/30\"}"), viewer)).isEqualTo(403);
        assertThat(status(json(post("/api/v1/traceroute"), "{\"target\":\"127.0.0.1\"}"), viewer)).isEqualTo(403);
    }

    @Test void operatorRunsOperationsButNotAdministration() throws Exception {
        assertThat(status(post("/api/v1/alerts/" + UUID.randomUUID() + "/acknowledge"), operator)).isEqualTo(404);
        assertThat(status(get("/api/v1/users"), operator)).isEqualTo(403);
        assertThat(status(get("/api/v1/audit"), operator)).isEqualTo(403);
        assertThat(status(get("/api/v1/notifications/channels"), operator)).isEqualTo(403);
        assertThat(status(json(post("/api/v1/alert-rules"), "{\"name\":\"r\",\"type\":\"DEVICE_DOWN\",\"severity\":\"INFO\",\"durationSeconds\":0,\"enabled\":true}"), operator)).isEqualTo(403);
        assertThat(status(json(put("/api/v1/settings"), "{\"degradedRttMs\":1,\"degradedLossPct\":1,\"downAfterFailures\":1,\"upAfterSuccesses\":1,\"defaultIntervalSeconds\":60,\"rawDays\":1,\"rollupDays\":1,\"scanCidrs\":[]}"), operator)).isEqualTo(403);
    }

    @Test void adminCanAdminister() throws Exception {
        assertThat(status(get("/api/v1/users"), admin)).isEqualTo(200);
        assertThat(status(get("/api/v1/audit"), admin)).isEqualTo(200);
    }

    @Test void anonymousIsRejected() throws Exception {
        for (String path : new String[]{"/api/v1/alerts", "/api/v1/users", "/api/v1/reports/uptime?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z", "/api/v1/audit"}) {
            mvc.perform(get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        }
    }

    @Test void usersNeverExposePasswordHashes() throws Exception {
        String body = mvc.perform(get("/api/v1/users").header("Authorization", admin)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("password");
    }

    @Test void lastAdminIsProtected() throws Exception {
        String last = tokenFor(Role.ADMIN);
        UUID id = idOf(last);
        jdbc.update("update app_user set enabled = false where role = 'ADMIN' and id <> ?", id);
        assertThat(status(json(put("/api/v1/users/" + id), "{\"role\":\"VIEWER\",\"enabled\":true}"), last)).isEqualTo(409);
        assertThat(status(delete("/api/v1/users/" + id), last)).isEqualTo(409);
    }

    @Test void disablingAUserRevokesTheirSessions() throws Exception {
        UUID u = jdbc.queryForObject("insert into app_user (id, username, password_hash, role) values (gen_random_uuid(), ?, 'x', 'OPERATOR') returning id", UUID.class, "op-" + UUID.randomUUID());
        jdbc.update("insert into refresh_token (id, user_id, token_hash, family_id, expires_at) values (gen_random_uuid(), ?, ?, gen_random_uuid(), now() + interval '1 day')", u, UUID.randomUUID().toString());
        assertThat(status(json(put("/api/v1/users/" + u), "{\"role\":\"OPERATOR\",\"enabled\":false}"), admin)).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id = ? and revoked_at is null", Integer.class, u)).isZero();
    }

    private UUID idOf(String token) {
        return jwt.verify(token.substring(7)).orElseThrow().userId();
    }

    @Test void revokedAccessEndsImmediately() throws Exception {
        String deleted = tokenFor(Role.ADMIN);
        assertThat(status(delete("/api/v1/users/" + idOf(deleted)), admin)).isEqualTo(204);
        assertThat(status(get("/api/v1/users"), deleted)).isEqualTo(401);

        String disabled = tokenFor(Role.ADMIN);
        assertThat(status(json(put("/api/v1/users/" + idOf(disabled)), "{\"role\":\"ADMIN\",\"enabled\":false}"), admin)).isEqualTo(200);
        assertThat(status(get("/api/v1/users"), disabled)).isEqualTo(401);

        String demoted = tokenFor(Role.ADMIN);
        assertThat(status(json(put("/api/v1/users/" + idOf(demoted)), "{\"role\":\"VIEWER\",\"enabled\":true}"), admin)).isEqualTo(200);
        assertThat(status(get("/api/v1/users"), demoted)).isEqualTo(401);
    }

    @Test void passwordResetEndsExistingTokens() throws Exception {
        String before = tokenFor(Role.ADMIN);
        Thread.sleep(1100);   // token issue times have one-second resolution
        assertThat(status(json(post("/api/v1/users/" + idOf(before) + "/password"), "{\"password\":\"Long-Enough-Pass-2\"}"), admin)).isEqualTo(204);
        assertThat(status(get("/api/v1/users"), before)).isEqualTo(401);
    }

    @Test void missingRoleIsReportedBeforeBodyValidation() throws Exception {
        assertThat(status(json(post("/api/v1/devices"), "{}"), viewer)).isEqualTo(403);
        assertThat(status(json(post("/api/v1/users"), "{}"), operator)).isEqualTo(403);
    }

    @Test void nonFiniteNumbersAreRefused() throws Exception {
        assertThat(status(json(post("/api/v1/alert-rules"), "{\"name\":\"nan\",\"type\":\"RTT_ABOVE\",\"severity\":\"INFO\",\"durationSeconds\":0,\"enabled\":false,\"threshold\":\"NaN\"}"), admin)).isEqualTo(400);
        String settings = "{\"degradedRttMs\":\"Infinity\",\"degradedLossPct\":10,\"downAfterFailures\":3,\"upAfterSuccesses\":2,\"defaultIntervalSeconds\":60,\"rawDays\":7,\"rollupDays\":90,\"scanCidrs\":[\"10.0.0.0/8\"]}";
        assertThat(status(json(put("/api/v1/settings"), settings), admin)).isEqualTo(400);
        String range = "from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z";
        assertThat(status(get("/api/v1/reports/uptime?" + range + "&slaTarget=NaN"), admin)).isEqualTo(400);
        assertThat(status(get("/api/v1/reports/uptime?" + range + "&deviceId=" + UUID.randomUUID()), admin)).isEqualTo(404);
    }

    @Test void userChangesAreAudited() throws Exception {
        String name = "new-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(status(json(post("/api/v1/users"), "{\"username\":\"%s\",\"password\":\"Long-Enough-Pass-1\",\"role\":\"VIEWER\"}".formatted(name)), admin)).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'USER_CREATE' and summary like ?", Integer.class, "%" + name + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select summary from audit_log where action = 'USER_CREATE' and summary like ?", String.class, "%" + name + "%")).doesNotContain("Long-Enough");
    }

    @Test void deviceEditsAreAuditedWithTheActor() throws Exception {
        String res = mvc.perform(post("/api/v1/devices").header("Authorization", operator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"audited\",\"host\":\"8.8.8.8\",\"type\":\"SERVER\"}")).andReturn().getResponse().getContentAsString();
        assertThat(res).contains("audited");
        assertThat(jdbc.queryForObject("select actor_name from audit_log where action = 'DEVICE_CREATE' order by id desc limit 1", String.class)).startsWith("user-");
    }

    @Test void settingsRejectAnUnsafeScanRangeAndApplyValidOnes() throws Exception {
        String base = "{\"degradedRttMs\":150,\"degradedLossPct\":10,\"downAfterFailures\":3,\"upAfterSuccesses\":2,\"defaultIntervalSeconds\":60,\"rawDays\":7,\"rollupDays\":90,\"scanCidrs\":[\"%s\"]}";
        assertThat(status(json(put("/api/v1/settings"), base.formatted("224.0.0.0/8")), admin)).isEqualTo(400);
        assertThat(status(json(put("/api/v1/settings"), base.formatted("0.0.0.0/0")), admin)).isEqualTo(400);
        assertThat(status(json(put("/api/v1/settings"), base.formatted("10.9.0.5/16")), admin)).isEqualTo(200);
        mvc.perform(get("/api/v1/settings").header("Authorization", viewer)).andExpect(jsonPath("$.scanCidrs[0]").value("10.9.0.0/16"));
        assertThat(status(json(post("/api/v1/discovery/scans"), "{\"cidr\":\"192.168.1.0/24\"}"), admin)).isEqualTo(403);
        jdbc.update("delete from app_settings");
        assertThat(status(json(put("/api/v1/settings"), base.formatted("127.0.0.0/8\",\"10.0.0.0/8\",\"172.16.0.0/12\",\"192.168.0.0/16")), admin)).isEqualTo(200);
        jdbc.update("delete from app_settings");
    }
}
