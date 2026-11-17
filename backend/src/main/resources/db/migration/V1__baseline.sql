-- NetPulse Phase 1 baseline schema.
-- Design notes (see docs/ARCHITECTURE.md):
--  * UUID keys for user-facing entities (not enumerable in URLs), BIGINT identity for high-volume results.
--  * One device_check row per monitoring cycle; ping/tcp/dns results are children of the cycle.
--    The cycle row stores the evaluated device status, so uptime% = UP-or-DEGRADED cycles / observed cycles.
--  * Child rows use ON DELETE CASCADE so retention only has to delete device_check rows.

CREATE TABLE app_user (
    id               UUID PRIMARY KEY,
    username         VARCHAR(64)  NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    role             VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'OPERATOR', 'VIEWER')),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts  INT          NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));

CREATE TABLE refresh_token (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64)   NOT NULL UNIQUE,          -- SHA-256 hex; the raw token only ever lives in the cookie
    family_id    UUID        NOT NULL,                  -- all rotations of one login share a family (reuse detection)
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_ip   VARCHAR(64),
    user_agent   VARCHAR(256)
);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
CREATE INDEX ix_refresh_token_expires ON refresh_token (expires_at);

CREATE TABLE device (
    id                     UUID PRIMARY KEY,
    name                   VARCHAR(100) NOT NULL,
    host                   VARCHAR(253) NOT NULL,
    type                   VARCHAR(16)  NOT NULL CHECK (type IN ('ROUTER', 'SWITCH', 'FIREWALL', 'SERVER', 'WEBSITE')),
    location               VARCHAR(100),
    description            VARCHAR(500),
    check_interval_seconds INT          NOT NULL CHECK (check_interval_seconds BETWEEN 10 AND 3600),
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    -- monitoring state (written by the engine, never by the CRUD API)
    status                 VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN'
                           CHECK (status IN ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN', 'PAUSED')),
    icmp_blocked           BOOLEAN      NOT NULL DEFAULT FALSE,
    consecutive_failures   INT          NOT NULL DEFAULT 0,
    consecutive_successes  INT          NOT NULL DEFAULT 0,
    resolved_ip            VARCHAR(45),
    last_rtt_ms            DOUBLE PRECISION,
    last_loss_pct          DOUBLE PRECISION,
    last_checked_at        TIMESTAMPTZ,
    next_check_at          TIMESTAMPTZ,
    status_changed_at      TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_device_name ON device (lower(name));
CREATE INDEX ix_device_due ON device (next_check_at) WHERE enabled;
CREATE INDEX ix_device_status ON device (status);

CREATE TABLE device_tag (
    device_id UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    tag       VARCHAR(32) NOT NULL,
    PRIMARY KEY (device_id, tag)
);
CREATE INDEX ix_device_tag_tag ON device_tag (tag);

CREATE TABLE device_port (
    id        UUID PRIMARY KEY,
    device_id UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    port      INT         NOT NULL CHECK (port BETWEEN 1 AND 65535),
    label     VARCHAR(32),
    UNIQUE (device_id, port)
);

CREATE TABLE device_dns_check (
    id          UUID PRIMARY KEY,
    device_id   UUID         NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    record_type VARCHAR(8)   NOT NULL CHECK (record_type IN ('A', 'AAAA', 'MX', 'NS', 'TXT', 'PTR')),
    query_name  VARCHAR(253),                 -- NULL for PTR means "reverse-lookup the device's own IP"
    dns_server  VARCHAR(253)                  -- NULL means the OS-configured resolver
);
CREATE INDEX ix_device_dns_check_device ON device_dns_check (device_id);

-- One row per monitoring cycle of one device.
CREATE TABLE device_check (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id    UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    checked_at   TIMESTAMPTZ NOT NULL,
    verdict      VARCHAR(16) NOT NULL CHECK (verdict IN ('UP', 'DEGRADED', 'FAIL', 'UNKNOWN')), -- raw result of this cycle
    status       VARCHAR(16) NOT NULL,                                                -- device status after flap logic
    icmp_blocked BOOLEAN     NOT NULL DEFAULT FALSE,
    resolved_ip  VARCHAR(45),
    error        VARCHAR(255),
    duration_ms  INT         NOT NULL
);
CREATE INDEX ix_device_check_device_time ON device_check (device_id, checked_at DESC);
CREATE INDEX ix_device_check_time ON device_check (checked_at);

CREATE TABLE ping_result (
    check_id         BIGINT PRIMARY KEY REFERENCES device_check (id) ON DELETE CASCADE,
    packets_sent     INT NOT NULL,
    packets_received INT NOT NULL,
    loss_pct         DOUBLE PRECISION NOT NULL,
    rtt_min          DOUBLE PRECISION,
    rtt_avg          DOUBLE PRECISION,
    rtt_max          DOUBLE PRECISION,
    jitter           DOUBLE PRECISION,
    error_category   VARCHAR(32),
    error_detail     VARCHAR(255)
);

CREATE TABLE tcp_result (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    check_id    BIGINT      NOT NULL REFERENCES device_check (id) ON DELETE CASCADE,
    port        INT         NOT NULL,
    state       VARCHAR(24) NOT NULL CHECK (state IN ('OPEN', 'CLOSED', 'FILTERED_OR_UNKNOWN')),
    connect_ms  DOUBLE PRECISION,
    error       VARCHAR(255)
);
CREATE INDEX ix_tcp_result_check ON tcp_result (check_id);

CREATE TABLE dns_result (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    check_id    BIGINT       NOT NULL REFERENCES device_check (id) ON DELETE CASCADE,
    record_type VARCHAR(8)   NOT NULL,
    query_name  VARCHAR(253) NOT NULL,
    dns_server  VARCHAR(253),
    outcome     VARCHAR(16)  NOT NULL CHECK (outcome IN ('NOERROR', 'NODATA', 'NXDOMAIN', 'SERVFAIL', 'REFUSED', 'TIMEOUT', 'ERROR')),
    resolve_ms  DOUBLE PRECISION,
    answers     TEXT,                   -- newline separated answer records
    error       VARCHAR(255)
);
CREATE INDEX ix_dns_result_check ON dns_result (check_id);

CREATE TABLE status_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id   UUID         NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    from_status VARCHAR(16)  NOT NULL,
    to_status   VARCHAR(16)  NOT NULL,
    reason      VARCHAR(255),
    occurred_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_status_event_device_time ON status_event (device_id, occurred_at DESC);
CREATE INDEX ix_status_event_time ON status_event (occurred_at DESC);

-- Hourly rollups, kept 90 days (raw device_check rows are kept 7 days).
CREATE TABLE rollup_hourly (
    device_id   UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    bucket      TIMESTAMPTZ NOT NULL,
    checks      INT         NOT NULL,
    up          INT         NOT NULL,
    degraded    INT         NOT NULL,
    down        INT         NOT NULL,
    rtt_avg     DOUBLE PRECISION,
    rtt_min     DOUBLE PRECISION,
    rtt_max     DOUBLE PRECISION,
    loss_avg    DOUBLE PRECISION,
    jitter_avg  DOUBLE PRECISION,
    PRIMARY KEY (device_id, bucket)
);
CREATE INDEX ix_rollup_hourly_bucket ON rollup_hourly (bucket);
