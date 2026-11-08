package com.netpulse.auth;

import com.netpulse.auth.dto.AuthDtos.UserInfo;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.error.ApiException;
import com.netpulse.user.AppUser;
import com.netpulse.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Login, refresh-token rotation and logout.
 *
 * <p>Rotation + reuse detection: each refresh consumes the presented token and issues a new one in the same
 * "family". If an already-consumed token is presented again, someone else (an attacker who stole it, or the
 * legitimate user after the attacker) is replaying it - so we revoke the whole family, forcing a fresh login.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final ApiException BAD_CREDENTIALS =
            new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid username or password");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final NetPulseProperties.Security cfg;
    private final Clock clock;
    /** Used to spend the same BCrypt time for unknown usernames (prevents user enumeration by timing). */
    private final String dummyHash;

    public AuthService(AppUserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                       JwtService jwt, NetPulseProperties props, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.cfg = props.security();
        this.clock = clock;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    public record AuthResult(JwtService.AccessToken access, String rawRefreshToken, UserInfo user) { }

    @Transactional(noRollbackFor = ApiException.class)   // failed-attempt counters must persist
    public AuthResult login(String username, String password, String ip, String userAgent) {
        Instant now = clock.instant();
        AppUser user = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (user == null) {
            encoder.matches(password, dummyHash);
            throw BAD_CREDENTIALS;
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.LOCKED, "Account locked",
                    "Too many failed attempts. Try again later.");
        }
        if (!user.isEnabled() || !encoder.matches(password, user.getPasswordHash())) {
            int attempts = user.getFailedAttempts() + 1;
            if (attempts >= cfg.loginMaxAttempts()) {
                user.setLockedUntil(now.plus(cfg.loginLockout()));
                user.setFailedAttempts(0);
                log.warn("Account '{}' locked after {} failed logins from {}", user.getUsername(), attempts, ip);
            } else {
                user.setFailedAttempts(attempts);
            }
            throw BAD_CREDENTIALS;
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        String raw = createRefreshToken(user, UUID.randomUUID(), ip, userAgent);
        log.info("User '{}' logged in from {}", user.getUsername(), ip);
        return new AuthResult(jwt.issue(user), raw, toInfo(user));
    }

    @Transactional(noRollbackFor = ApiException.class)   // family revocation must persist
    public AuthResult refresh(String rawToken, String ip, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) throw unauthorized();
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(sha256(rawToken)).orElseThrow(AuthService::unauthorized);
        if (token.getRevokedAt() != null) {
            int n = refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user '{}' - revoked {} tokens in family",
                    token.getUser().getUsername(), n);
            throw unauthorized();
        }
        if (!token.isActive(now) || !token.getUser().isEnabled()) throw unauthorized();

        token.setRevokedAt(now);   // rotate: the presented token is now consumed
        String raw = createRefreshToken(token.getUser(), token.getFamilyId(), ip, userAgent);
        return new AuthResult(jwt.issue(token.getUser()), raw, toInfo(token.getUser()));
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        refreshTokens.findByTokenHash(sha256(rawToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int n = refreshTokens.deleteExpired(clock.instant());
        if (n > 0) log.info("Purged {} expired refresh tokens", n);
    }

    private String createRefreshToken(AppUser user, UUID family, String ip, String userAgent) {
        byte[] bytes = new byte[32];                     // 256 bits of entropy
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken t = new RefreshToken();
        t.setUser(user);
        t.setTokenHash(sha256(raw));
        t.setFamilyId(family);
        t.setExpiresAt(clock.instant().plus(cfg.refreshTokenTtl()));
        t.setCreatedIp(ip);
        t.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(256, userAgent.length())));
        refreshTokens.save(t);
        return raw;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Session expired. Please sign in again.");
    }

    public static UserInfo toInfo(AppUser u) {
        return new UserInfo(u.getId(), u.getUsername(), u.getRole());
    }
}
