package com.netpulse.user;

import com.netpulse.common.config.NetPulseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN from ADMIN_USERNAME / ADMIN_PASSWORD on first startup (empty user table only).
 * Nothing is hard-coded for prod: without the env vars, no account is created and a warning is logged.
 */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final NetPulseProperties props;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder encoder, NetPulseProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        String username = props.security().adminUsername();
        String password = props.security().adminPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("No users exist and ADMIN_USERNAME/ADMIN_PASSWORD are not set - nobody can log in.");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        AppUser admin = new AppUser();
        admin.setUsername(username.trim());
        admin.setPasswordHash(encoder.encode(password));
        admin.setRole(Role.ADMIN);
        users.save(admin);
        log.info("Created initial admin account '{}'", admin.getUsername());
    }
}
