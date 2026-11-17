package com.netpulse.auth;

import com.netpulse.user.AppUser;
import com.netpulse.user.AppUserRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * A valid signature only proves what was true when the token was issued. This also checks the account as it is now,
 * so deleting, disabling or changing the role of a user, or resetting their password, takes effect immediately.
 */
@Service
public class SessionVerifier {
    private final JwtService jwt;
    private final AppUserRepository users;
    private final Clock clock;

    public SessionVerifier(JwtService jwt, AppUserRepository users, Clock clock) {
        this.jwt = jwt;
        this.users = users;
        this.clock = clock;
    }

    public Optional<JwtService.Principal> verify(String token) {
        return jwt.verify(token).filter(p -> allows(p, users.findById(p.userId()).orElse(null)));
    }

    /** @param account the token owner's current record, or null if the account no longer exists */
    public boolean allows(JwtService.Principal token, AppUser account) {
        return account != null
                && account.isEnabled()
                && account.getRole() == token.role()
                && token.expiresAt().isAfter(clock.instant())
                && (account.getSessionsValidAfter() == null || !token.issuedAt().isBefore(account.getSessionsValidAfter()));
    }
}
