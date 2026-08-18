CREATE TABLE iam.public_contact_request (
    id UUID PRIMARY KEY,
    request_type VARCHAR(16) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    work_email VARCHAR(254) NOT NULL,
    company_name VARCHAR(160),
    message VARCHAR(4000) NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_public_contact_request_type CHECK (request_type IN ('DEMO', 'CONTACT')),
    CONSTRAINT ck_public_contact_request_source CHECK (source IN ('WEBSITE')),
    CONSTRAINT ck_public_contact_request_status CHECK (status IN ('RECEIVED', 'IN_REVIEW', 'CLOSED')),
    CONSTRAINT ck_public_contact_request_message CHECK (char_length(btrim(message)) BETWEEN 20 AND 4000)
);

CREATE INDEX ix_public_contact_request_status_received
    ON iam.public_contact_request (status, received_at DESC);
CREATE INDEX ix_public_contact_request_email_received
    ON iam.public_contact_request (work_email, received_at DESC);

CREATE TABLE iam.public_contact_throttle_bucket (
    throttle_dimension VARCHAR(16) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (throttle_dimension, key_hash),
    CONSTRAINT ck_public_contact_throttle_dimension CHECK (throttle_dimension IN ('EMAIL', 'IP')),
    CONSTRAINT ck_public_contact_throttle_count CHECK (request_count >= 0)
);

CREATE INDEX ix_public_contact_throttle_updated_at
    ON iam.public_contact_throttle_bucket (updated_at);
