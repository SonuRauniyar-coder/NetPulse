package com.netpulse.user;

import com.netpulse.audit.AuditService;
import com.netpulse.common.error.ApiException;
import com.netpulse.common.web.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class UserService {
    public record UserDto(UUID id, String username, Role role, boolean enabled, boolean locked, Instant lastLoginAt, Instant createdAt) { }

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public UserService(AppUserRepository users, PasswordEncoder encoder, JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        return users.findAll().stream().sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername())).map(this::toDto).toList();
    }

    @Transactional
    public UserDto create(String username, String password, Role role) {
        requireBcryptLength(password);
        String name = username.trim();
        if (users.findByUsernameIgnoreCase(name).isPresent()) throw ApiException.conflict("A user named '" + name + "' already exists");
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        users.save(u);
        audit.record("USER_CREATE", "user", u.getId(), "Created user '" + name + "' with role " + role);
        return toDto(u);
    }

    @Transactional
    public UserDto update(UUID id, Role role, boolean enabled) {
        lockAdminChanges();
        AppUser u = load(id);
        boolean self = CurrentUser.get().map(p -> p.userId().equals(id)).orElse(false);
        if (self && !enabled) throw ApiException.conflict("You can't disable your own account.");
        boolean losesAdmin = u.getRole() == Role.ADMIN && u.isEnabled() && (role != Role.ADMIN || !enabled);
        if (losesAdmin) requireAnotherAdmin();
        boolean accessChanged = u.getRole() != role || u.isEnabled() != enabled;
        String change = "User '" + u.getUsername() + "': role " + u.getRole() + " -> " + role + ", enabled " + u.isEnabled() + " -> " + enabled;
        u.setRole(role);
        u.setEnabled(enabled);
        if (accessChanged) {
            revokeSessions(id);
            audit.record("USER_UPDATE", "user", id, change);
        }
        return toDto(u);
    }

    @Transactional
    public void resetPassword(UUID id, String password) {
        requireBcryptLength(password);
        AppUser u = load(id);
        u.setPasswordHash(encoder.encode(password));
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setSessionsValidAfter(clock.instant().truncatedTo(ChronoUnit.SECONDS));   // JWT iat has whole-second precision
        revokeSessions(id);
        audit.record("USER_PASSWORD_RESET", "user", id, "Password reset for '" + u.getUsername() + "'");
    }

    @Transactional
    public void delete(UUID id) {
        lockAdminChanges();
        AppUser u = load(id);
        if (CurrentUser.get().map(p -> p.userId().equals(id)).orElse(false)) throw ApiException.conflict("You can't delete your own account.");
        if (u.getRole() == Role.ADMIN && u.isEnabled()) requireAnotherAdmin();
        users.delete(u);
        audit.record("USER_DELETE", "user", id, "Deleted user '" + u.getUsername() + "'");
    }

    /** The request limit counts characters, but BCrypt's limit is 72 bytes and a multi-byte password can exceed it. */
    private static void requireBcryptLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw ApiException.badRequest("That password is too long. Use at most 72 bytes.");
    }

    /** Without this, two admins demoting each other at the same moment could leave nobody able to administer the system. */
    private void lockAdminChanges() {
        jdbc.query("select pg_advisory_xact_lock(4242)", rs -> { });
    }

    private void requireAnotherAdmin() {
        Long admins = jdbc.queryForObject("select count(*) from app_user where role = 'ADMIN' and enabled", Long.class);
        if (Objects.requireNonNullElse(admins, 0L) <= 1) throw ApiException.conflict("This is the last active admin. Promote someone else first.");
    }

    private void revokeSessions(UUID userId) {
        jdbc.update("update refresh_token set revoked_at = ? where user_id = ? and revoked_at is null", Timestamp.from(clock.instant()), userId);
    }

    private AppUser load(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    private UserDto toDto(AppUser u) {
        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(clock.instant());
        return new UserDto(u.getId(), u.getUsername(), u.getRole(), u.isEnabled(), locked, u.getLastLoginAt(), u.getCreatedAt());
    }
}
