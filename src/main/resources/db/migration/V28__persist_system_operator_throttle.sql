CREATE TABLE iam.system_operator_throttle_bucket (
    bucket_key_hash CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_system_operator_throttle_count CHECK (failure_count >= 0)
);
CREATE INDEX ix_system_operator_throttle_updated ON iam.system_operator_throttle_bucket (updated_at);
