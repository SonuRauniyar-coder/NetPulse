package com.netpulse.auth;

import com.netpulse.user.AppUser;
import com.netpulse.user.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SessionVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    private final SessionVerifier verifier = new SessionVerifier(null, null, Clock.fixed(NOW, ZoneOffset.UTC));

    private AppUser account() {
        AppUser u = new AppUser();
        u.setRole(Role.OPERATOR);
        return u;
    }

    private JwtService.Principal token(AppUser u, Role role, Instant issued, Duration life) {
        return new JwtService.Principal(u.getId(), "x", role, issued, issued.plus(life));
    }

    @Test void currentAccountAndUnexpiredTokenAreAllowed() {
        AppUser u = account();
        assertThat(verifier.allows(token(u, Role.OPERATOR, NOW, Duration.ofMinutes(15)), u)).isTrue();
    }

    @Test void expiredTokenIsRefused() {
        AppUser u = account();
        assertThat(verifier.allows(token(u, Role.OPERATOR, NOW.minusSeconds(60), Duration.ofSeconds(60)), u)).isFalse();
    }

    @Test void missingDisabledOrChangedAccountIsRefused() {
        AppUser u = account();
        JwtService.Principal t = token(u, Role.OPERATOR, NOW, Duration.ofMinutes(15));
        assertThat(verifier.allows(t, null)).isFalse();
        assertThat(verifier.allows(token(u, Role.ADMIN, NOW, Duration.ofMinutes(15)), u)).isFalse();
        u.setEnabled(false);
        assertThat(verifier.allows(t, u)).isFalse();
    }

    @Test void tokenFromBeforeTheCutoffIsRefused() {
        AppUser u = account();
        u.setSessionsValidAfter(NOW);
        assertThat(verifier.allows(token(u, Role.OPERATOR, NOW.minusSeconds(1), Duration.ofMinutes(15)), u)).isFalse();
        assertThat(verifier.allows(token(u, Role.OPERATOR, NOW, Duration.ofMinutes(15)), u)).isTrue();
    }
}
