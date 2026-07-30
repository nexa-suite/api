CREATE TABLE iam.authentication_failure (
    id UUID PRIMARY KEY,
    normalized_identifier VARCHAR(254) NOT NULL,
    client_fingerprint VARCHAR(128) NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 0,
    window_started_at TIMESTAMPTZ NOT NULL,
    last_failure_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_authentication_failure_key UNIQUE (normalized_identifier, client_fingerprint)
);

CREATE INDEX ix_authentication_failure_window ON iam.authentication_failure (window_started_at);
