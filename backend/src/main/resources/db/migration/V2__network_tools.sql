-- Phase 2: discovery, SNMP. The audit log and settings are here too because scans must be audited and authorized.

-- One row, created on the first save. Until then the values in application.yml apply.
CREATE TABLE app_settings (
    id                INT PRIMARY KEY CHECK (id = 1),
    degraded_rtt_ms   DOUBLE PRECISION NOT NULL,
    degraded_loss_pct DOUBLE PRECISION NOT NULL,
    down_after        INT NOT NULL,
    up_after          INT NOT NULL,
    default_interval  INT NOT NULL,
    raw_days          INT NOT NULL,
    rollup_days       INT NOT NULL,
    scan_cidrs        TEXT NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Who changed what, and when. actor_name is copied so entries survive the user being deleted.
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    UUID,
    actor_name  VARCHAR(64) NOT NULL,
    action      VARCHAR(48) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id   VARCHAR(64),
    summary     VARCHAR(500) NOT NULL,
    client_ip   VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_audit_log_time ON audit_log (occurred_at DESC);
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id);

CREATE TABLE discovery_scan (
    id           UUID PRIMARY KEY,
    cidr         VARCHAR(18) NOT NULL,
    status       VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'INTERRUPTED')),
    requested_by VARCHAR(64) NOT NULL,
    total        INT NOT NULL,
    scanned      INT NOT NULL DEFAULT 0,
    found        INT NOT NULL DEFAULT 0,
    error        VARCHAR(255),
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX ix_discovery_scan_started ON discovery_scan (started_at DESC);

CREATE TABLE discovery_host (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scan_id        UUID NOT NULL REFERENCES discovery_scan (id) ON DELETE CASCADE,
    ip             VARCHAR(45) NOT NULL,
    mac            VARCHAR(17),
    vendor         VARCHAR(160),
    hostname       VARCHAR(253),
    rtt_ms         DOUBLE PRECISION,
    icmp_responded BOOLEAN NOT NULL,
    UNIQUE (scan_id, ip)
);

-- credentials is AES-GCM ciphertext (see CredentialCipher) and no query ever returns it to a client.
CREATE TABLE snmp_config (
    device_id        UUID PRIMARY KEY REFERENCES device (id) ON DELETE CASCADE,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    version          VARCHAR(8) NOT NULL CHECK (version IN ('V2C', 'V3')),
    port             INT NOT NULL DEFAULT 161 CHECK (port BETWEEN 1 AND 65535),
    interval_seconds INT NOT NULL CHECK (interval_seconds BETWEEN 10 AND 3600),
    credentials      TEXT NOT NULL,
    next_poll_at     TIMESTAMPTZ,
    sys_name         VARCHAR(255),
    sys_descr        VARCHAR(1024),
    sys_location     VARCHAR(255),
    sys_uptime_ticks BIGINT,
    last_poll_at     TIMESTAMPTZ,
    last_success_at  TIMESTAMPTZ,
    last_error       VARCHAR(255)
);
CREATE INDEX ix_snmp_config_due ON snmp_config (next_poll_at) WHERE enabled;

-- Octet counters are unsigned 64-bit, which BIGINT cannot hold, hence NUMERIC(20).
CREATE TABLE snmp_interface (
    device_id       UUID NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    if_index        INT NOT NULL,
    if_descr        VARCHAR(255),
    admin_status    SMALLINT,
    oper_status     SMALLINT,
    speed_bps       BIGINT,
    in_octets       NUMERIC(20, 0),
    out_octets      NUMERIC(20, 0),
    counter_bits    SMALLINT,
    in_errors       NUMERIC(20, 0),
    out_errors      NUMERIC(20, 0),
    in_bps          DOUBLE PRECISION,
    out_bps         DOUBLE PRECISION,
    utilization_pct DOUBLE PRECISION,
    last_seen_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (device_id, if_index)
);

CREATE TABLE snmp_sample (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id       UUID NOT NULL,
    if_index        INT NOT NULL,
    sampled_at      TIMESTAMPTZ NOT NULL,
    in_bps          DOUBLE PRECISION NOT NULL,
    out_bps         DOUBLE PRECISION NOT NULL,
    utilization_pct DOUBLE PRECISION,
    FOREIGN KEY (device_id, if_index) REFERENCES snmp_interface (device_id, if_index) ON DELETE CASCADE
);
CREATE INDEX ix_snmp_sample_if_time ON snmp_sample (device_id, if_index, sampled_at DESC);
CREATE INDEX ix_snmp_sample_time ON snmp_sample (sampled_at);
