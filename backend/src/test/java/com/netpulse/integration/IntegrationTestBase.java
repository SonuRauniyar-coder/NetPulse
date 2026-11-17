package com.netpulse.integration;

import com.netpulse.auth.JwtService;
import com.netpulse.user.AppUser;
import com.netpulse.user.AppUserRepository;
import com.netpulse.user.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/** Real PostgreSQL via Testcontainers (no H2): date_bin, FILTER and FOR UPDATE must be tested on the real engine. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();   // one container shared by all integration test classes
    }

    @Autowired protected JwtService jwt;
    @Autowired protected AppUserRepository users;
    @Autowired protected JdbcTemplate jdbc;
    @LocalServerPort protected int port;

    protected String tokenFor(Role role) {
        AppUser u = new AppUser();
        u.setUsername("user-" + UUID.randomUUID());
        u.setPasswordHash("not-used");
        u.setRole(role);
        users.save(u);
        return "Bearer " + jwt.issue(u).token();
    }
}
