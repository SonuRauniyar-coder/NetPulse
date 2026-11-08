package com.netpulse.auth;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.user.AppUser;
import com.netpulse.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies short-lived access tokens (HS256-signed JWT).
 * Access tokens are stateless: the server does not store them, which is why they must be short-lived;
 * revocation happens by refusing to refresh (refresh tokens ARE stored, hashed).
 */
@Service
public class JwtService {
    private static final String ISSUER = "netpulse";
    private final SecretKey key;
    private final NetPulseProperties.Security cfg;
    private final Clock clock;

    public JwtService(NetPulseProperties props, Clock clock) {
        this.cfg = props.security();
        this.clock = clock;
        String secret = cfg.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes long");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public record AccessToken(String token, Instant expiresAt) { }

    public record Principal(UUID userId, String username, Role role) { }

    public AccessToken issue(AppUser user) {
        Instant now = clock.instant();
        Instant exp = now.plus(cfg.accessTokenTtl());
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new AccessToken(token, exp);
    }

    public Optional<Principal> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new Principal(UUID.fromString(c.getSubject()),
                    c.get("username", String.class), Role.valueOf(c.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
