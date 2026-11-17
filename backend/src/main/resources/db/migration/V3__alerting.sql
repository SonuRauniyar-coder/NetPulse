-- Phase 3: alert rules and lifecycle, maintenance windows, notification channels and delivery.

CREATE TABLE alert_rule (
    id               UUID PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    type             VARCHAR(24) NOT NULL CHECK (type IN ('DEVICE_DOWN', 'RTT_ABOVE', 'LOSS_ABOVE', 'PORT_CLOSED', 'INTERFACE_DOWN', 'INTERFACE_UTILIZATION')),
    severity         VARCHAR(10) NOT NULL CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    device_id        UUID REFERENCES device (id) ON DELETE CASCADE,    -- NULL applies the rule to every device
    threshold        DOUBLE PRECISION,
    port             INT CHECK (port BETWEEN 1 AND 65535),             -- PORT_CLOSED: NULL means every configured port
    interface_name   VARCHAR(255),                                     -- interface rules: NULL means every interface
    duration_seconds INT NOT NULL DEFAULT 0 CHECK (duration_seconds BETWEEN 0 AND 86400),
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_alert_rule_name ON alert_rule (lower(name));

CREATE TABLE alert (
    id               UUID PRIMARY KEY,
    rule_id          UUID REFERENCES alert_rule (id) ON DELETE SET NULL,
    rule_name        VARCHAR(100) NOT NULL,
    device_id        UUID NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    subject          VARCHAR(300) NOT NULL DEFAULT '',                 -- '' for the device, else 'port:443' or 'if:3'
    severity         VARCHAR(10) NOT NULL,
    state            VARCHAR(16) NOT NULL CHECK (state IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED')),
    message          VARCHAR(500) NOT NULL,
    value            DOUBLE PRECISION,
    triggered_at     TIMESTAMPTZ NOT NULL,
    acknowledged_at  TIMESTAMPTZ,
    acknowledged_by  VARCHAR(64),
    resolved_at      TIMESTAMPTZ,
    resolve_reason   VARCHAR(255)
);
-- The database itself guarantees one open alert per condition, however evaluations interleave.
CREATE UNIQUE INDEX ux_alert_open ON alert (rule_id, device_id, subject) WHERE state <> 'RESOLVED';
CREATE INDEX ix_alert_state_time ON alert (state, triggered_at DESC);
CREATE INDEX ix_alert_device_time ON alert (device_id, triggered_at DESC);

-- A condition being timed against the rule's "for X minutes". In the database so a restart doesn't reset the clock.
CREATE TABLE alert_pending (
    rule_id         UUID NOT NULL REFERENCES alert_rule (id) ON DELETE CASCADE,
    device_id       UUID NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    subject         VARCHAR(300) NOT NULL DEFAULT '',
    breaching_since TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (rule_id, device_id, subject)
);

CREATE TABLE maintenance_window (
    id         UUID PRIMARY KEY,
    device_id  UUID NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    reason     VARCHAR(255),
    created_by VARCHAR(64) NOT NULL,
    CHECK (ends_at > starts_at)
);
CREATE INDEX ix_maintenance_device_time ON maintenance_window (device_id, starts_at, ends_at);

CREATE TABLE notification_channel (
    id               UUID PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    type             VARCHAR(8) NOT NULL CHECK (type IN ('EMAIL', 'WEBHOOK')),
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    min_severity     VARCHAR(10) NOT NULL CHECK (min_severity IN ('CRITICAL', 'WARNING', 'INFO')),
    recipients       VARCHAR(1000),                 -- EMAIL: comma separated
    webhook_format   VARCHAR(10) CHECK (webhook_format IN ('SLACK', 'DISCORD', 'TELEGRAM', 'GENERIC')),
    webhook_url_enc  TEXT,                          -- the path of a webhook URL is its secret, so it is encrypted
    webhook_host     VARCHAR(253),                  -- safe to display
    telegram_chat_id VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_notification_channel_name ON notification_channel (lower(name));

-- A transactional outbox: rows commit with the alert change that caused them, and a worker delivers them later.
CREATE TABLE notification_delivery (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id      UUID NOT NULL REFERENCES notification_channel (id) ON DELETE CASCADE,
    alert_id        UUID NOT NULL REFERENCES alert (id) ON DELETE CASCADE,
    event           VARCHAR(16) NOT NULL,
    subject         VARCHAR(300) NOT NULL,
    body            VARCHAR(1000) NOT NULL,
    status          VARCHAR(8) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL,
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_notification_due ON notification_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_notification_created ON notification_delivery (created_at DESC);
