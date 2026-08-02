ALTER TABLE tenant_management.organization_registration
    ADD COLUMN status_token_hash CHAR(64);

UPDATE tenant_management.organization_registration
SET status_token_hash = repeat('0', 64)
WHERE status_token_hash IS NULL;

ALTER TABLE tenant_management.organization_registration
    ALTER COLUMN status_token_hash SET NOT NULL;

CREATE INDEX ix_organization_registration_status_handle
    ON tenant_management.organization_registration (id, status_token_hash, status);

CREATE TABLE iam.password_reset_throttle_bucket (
	    throttle_dimension VARCHAR(8) NOT NULL,
	    key_hash CHAR(64) NOT NULL,
	    window_started_at TIMESTAMPTZ NOT NULL,
	    request_count INTEGER NOT NULL DEFAULT 0,
	    updated_at TIMESTAMPTZ NOT NULL,
	    PRIMARY KEY (throttle_dimension, key_hash),
	    CONSTRAINT ck_password_reset_throttle_dimension CHECK (throttle_dimension IN ('EMAIL', 'IP')),
	    CONSTRAINT ck_password_reset_throttle_count CHECK (request_count >= 0)
);
CREATE INDEX ix_password_reset_throttle_window
    ON iam.password_reset_throttle_bucket (window_started_at);

CREATE TABLE iam.security_notification_outbox (
    id UUID PRIMARY KEY,
    notification_type VARCHAR(64) NOT NULL,
    recipient VARCHAR(254) NOT NULL,
    surface VARCHAR(32) NOT NULL,
    payload_ciphertext TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_security_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT ck_security_outbox_attempts CHECK (attempt_count BETWEEN 0 AND 12)
);
CREATE INDEX ix_security_outbox_delivery
    ON iam.security_notification_outbox (status, next_attempt_at, created_at);
