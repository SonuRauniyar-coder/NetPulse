package com.netpulse.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netpulse.user.AppUser;
import com.netpulse.user.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIT extends IntegrationTestBase {
    private static final String PASSWORD = "Test-Admin-Pass-1!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder encoder;

    private Cookie csrfCookie() throws Exception {
        return mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MockHttpServletResponse login(String user, String pass) throws Exception {
        Cookie csrf = csrfCookie();
        return mvc.perform(post("/api/v1/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", user, "password", pass))))
                .andReturn().getResponse();
    }

    private Cookie refreshCookie(MockHttpServletResponse r) {
        String setCookie = r.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("np_refresh=");
        return new Cookie("np_refresh", setCookie.substring("np_refresh=".length(), setCookie.indexOf(';')));
    }

    private MockHttpServletResponse refresh(Cookie refresh) throws Exception {
        Cookie csrf = csrfCookie();
        return mvc.perform(post("/api/v1/auth/refresh").cookie(csrf, refresh).header("X-XSRF-TOKEN", csrf.getValue()))
                .andReturn().getResponse();
    }

    private String createUser() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setRole(Role.ADMIN);
        users.save(u);
        return name;
    }

    @Test void apiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/devices")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test void garbageTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/devices").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
    }

    @Test void loginWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void refreshWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isForbidden());
    }

    @Test void bootstrappedAdminCanLoginAndCookieIsHardened() throws Exception {
        MockHttpServletResponse r = login("admin", PASSWORD);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsString()).contains("accessToken").doesNotContain("passwordHash").doesNotContain("np_refresh");
        assertThat(r.getHeader(HttpHeaders.SET_COOKIE)).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth");

        String token = mapper.readTree(r.getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
    }

    @Test void wrongPasswordAndUnknownUserLookIdentical() throws Exception {
        MockHttpServletResponse wrong = login(createUser(), "wrong-password");
        MockHttpServletResponse unknown = login("no-such-user", "wrong-password");
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(unknown.getStatus()).isEqualTo(401);
        assertThat(mapper.readTree(wrong.getContentAsString()).get("detail"))
                .isEqualTo(mapper.readTree(unknown.getContentAsString()).get("detail"));
    }

    @Test void accountLocksAfterRepeatedFailures() throws Exception {
        String user = createUser();
        for (int i = 0; i < 5; i++) assertThat(login(user, "wrong").getStatus()).isEqualTo(401);
        assertThat(login(user, PASSWORD).getStatus()).isEqualTo(401);
    }

    @Test void refreshRotatesToken() throws Exception {
        Cookie first = refreshCookie(login(createUser(), PASSWORD));
        MockHttpServletResponse r = refresh(first);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(refreshCookie(r).getValue()).isNotEqualTo(first.getValue());
    }

    @Test void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
        Cookie first = refreshCookie(login(createUser(), PASSWORD));
        Cookie second = refreshCookie(refresh(first));
        assertThat(refresh(first).getStatus()).isEqualTo(401);    // replay detected
        assertThat(refresh(second).getStatus()).isEqualTo(401);   // the newer token dies too
    }

    @Test void refreshTokenIsStoredHashed() throws Exception {
        Cookie c = refreshCookie(login(createUser(), PASSWORD));
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where token_hash = ?", Integer.class, c.getValue())).isZero();
    }

    @Test void logoutRevokes() throws Exception {
        Cookie refresh = refreshCookie(login(createUser(), PASSWORD));
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/v1/auth/logout").cookie(csrf, refresh).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
        assertThat(refresh(refresh).getStatus()).isEqualTo(401);
    }

    /** Regression: a JWT-authenticated call must not rotate the CSRF cookie out from under a concurrent refresh. */
    @Test void bearerRequestDoesNotRotateCsrfCookie() throws Exception {
        String token = mapper.readTree(login("admin", PASSWORD).getContentAsString()).get("accessToken").asText();
        Cookie csrf = csrfCookie();
        MockHttpServletResponse r = mvc.perform(get("/api/v1/devices").cookie(csrf).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getCookie("XSRF-TOKEN")).isNull();
    }

    @Test void healthIsPublicButMetricsListIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
