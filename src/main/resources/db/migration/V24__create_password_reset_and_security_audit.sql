ALTER TABLE iam.user_account ADD COLUMN phone VARCHAR(64);
ALTER TABLE iam.user_account ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';
ALTER TABLE iam.refresh_session ADD COLUMN last_seen_at TIMESTAMPTZ;
ALTER TABLE iam.refresh_session ADD COLUMN device_label VARCHAR(160);
ALTER TABLE iam.refresh_session ADD COLUMN coarse_ip VARCHAR(64);

CREATE TABLE iam.password_reset_request (
    id UUID PRIMARY KEY,
    normalized_email VARCHAR(254) NOT NULL,
    surface VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_password_reset_status CHECK (status IN ('PENDING', 'CONSUMED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_password_reset_attempts CHECK (attempts BETWEEN 0 AND 10)
);
CREATE INDEX ix_password_reset_email_status ON iam.password_reset_request (normalized_email, status, created_at DESC);
CREATE INDEX ix_password_reset_expiry ON iam.password_reset_request (expires_at);

CREATE TABLE iam.security_audit_event (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    actor_user_id UUID,
    target_user_id UUID,
    tenant_id UUID,
    workspace_id UUID,
    surface VARCHAR(32),
    correlation_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_security_audit_occurred_at ON iam.security_audit_event (occurred_at DESC);
CREATE INDEX ix_security_audit_target ON iam.security_audit_event (target_user_id, occurred_at DESC);
CREATE INDEX ix_security_audit_scope ON iam.security_audit_event (tenant_id, workspace_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION iam.prevent_security_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'security audit events are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER security_audit_event_no_update
    BEFORE UPDATE OR DELETE ON iam.security_audit_event
    FOR EACH ROW EXECUTE FUNCTION iam.prevent_security_audit_mutation();
