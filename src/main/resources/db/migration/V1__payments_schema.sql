-- Money columns are BIGINT minor units (cents). Never DECIMAL floats, never doubles.

CREATE TABLE payments (
    id                   VARCHAR(40)  NOT NULL PRIMARY KEY,
    merchant_reference   VARCHAR(64)  NOT NULL,
    amount_minor         BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    psp_reference        VARCHAR(64),
    captured_minor       BIGINT       NOT NULL DEFAULT 0,
    refunded_minor       BIGINT       NOT NULL DEFAULT 0,
    pending_refund_minor BIGINT       NOT NULL DEFAULT 0,
    failure_code         VARCHAR(64),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL
);
CREATE UNIQUE INDEX ux_payments_psp_reference ON payments (psp_reference);
CREATE INDEX ix_payments_status_created ON payments (status, created_at);
CREATE INDEX ix_payments_created ON payments (created_at);

CREATE TABLE refunds (
    id            VARCHAR(40)  NOT NULL PRIMARY KEY,
    payment_id    VARCHAR(40)  NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    reason        VARCHAR(255),
    status        VARCHAR(16)  NOT NULL,
    psp_reference VARCHAR(64),
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);
CREATE INDEX ix_refunds_payment ON refunds (payment_id);

CREATE TABLE disputes (
    id             VARCHAR(40)  NOT NULL PRIMARY KEY,
    payment_id     VARCHAR(40)  NOT NULL,
    psp_dispute_id VARCHAR(64)  NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    reason         VARCHAR(255),
    status         VARCHAR(16)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    CONSTRAINT fk_disputes_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);
CREATE UNIQUE INDEX ux_disputes_psp_dispute ON disputes (psp_dispute_id);
CREATE INDEX ix_disputes_payment ON disputes (payment_id);

-- Transactional outbox: written in the same transaction as the state change, relayed to Kafka.
CREATE TABLE outbox_events (
    id           VARCHAR(40)  NOT NULL PRIMARY KEY,
    aggregate_id VARCHAR(40)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6),
    attempts     INT          NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (published_at, created_at);
CREATE INDEX ix_outbox_aggregate ON outbox_events (aggregate_id, created_at);

-- id = sha256(scope + key), so the primary key itself resolves concurrent retries.
CREATE TABLE idempotency_keys (
    id              CHAR(64)     NOT NULL PRIMARY KEY,
    scope           VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    response_status INT,
    response_body   TEXT,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL
);
CREATE INDEX ix_idempotency_created ON idempotency_keys (created_at);

-- One row per PSP event id: makes webhook processing idempotent.
CREATE TABLE webhook_events (
    event_id    VARCHAR(255) NOT NULL PRIMARY KEY,
    event_type  VARCHAR(64)  NOT NULL,
    outcome     VARCHAR(32)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    received_at DATETIME(6)  NOT NULL
);
