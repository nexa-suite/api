-- Provider-neutral BC-10 native push routing foundation. Provider tokens are
-- hashed; business notification facts remain the only source payload.

CREATE TABLE notifications.push_subscription (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    recipient_membership_id UUID NOT NULL,
    user_id UUID NOT NULL,
    surface VARCHAR(16) NOT NULL,
    installation_id VARCHAR(160) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    provider_token_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_push_subscription_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT uq_push_subscription_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_push_subscription_installation UNIQUE (tenant_id, workspace_id, recipient_membership_id, installation_id),
    CONSTRAINT ck_push_subscription_hash CHECK (provider_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_push_subscription_platform CHECK (platform IN ('IOS','ANDROID')),
    CONSTRAINT ck_push_subscription_surface CHECK (surface IN ('PLATFORM','PORTAL')),
    CONSTRAINT ck_push_subscription_status CHECK (status IN ('ENABLED','DISABLED','UNREGISTERED')),
    CONSTRAINT ck_push_subscription_key CHECK (length(btrim(installation_id)) BETWEEN 1 AND 160),
    CONSTRAINT ck_push_subscription_version CHECK (version >= 0)
);

CREATE INDEX ix_push_subscription_recipient ON notifications.push_subscription
    (tenant_id, workspace_id, recipient_membership_id, status, id);

CREATE TABLE notifications.push_subscription_command_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    subscription_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_push_subscription_idempotency PRIMARY KEY (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key),
    CONSTRAINT fk_push_subscription_idempotency_subscription FOREIGN KEY (tenant_id, workspace_id, subscription_id)
        REFERENCES notifications.push_subscription (tenant_id, workspace_id, id),
    CONSTRAINT ck_push_subscription_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_push_subscription_idempotency_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160)
);

CREATE TABLE notifications.push_delivery_attempt (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    error VARCHAR(2000),
    attempt_number INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_push_attempt_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_push_attempt_subscription FOREIGN KEY (tenant_id, workspace_id, subscription_id)
        REFERENCES notifications.push_subscription (tenant_id, workspace_id, id),
    CONSTRAINT ck_push_attempt_status CHECK (status IN ('DEFERRED','SENT','FAILED','RETRYABLE')),
    CONSTRAINT ck_push_attempt_number CHECK (attempt_number > 0)
);

CREATE INDEX ix_push_attempt_event ON notifications.push_delivery_attempt
    (tenant_id, workspace_id, event_id, subscription_id, created_at);

CREATE TRIGGER notifications_push_subscription_idempotency_append_only
    BEFORE UPDATE OR DELETE ON notifications.push_subscription_command_idempotency FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER notifications_push_delivery_attempt_append_only
    BEFORE UPDATE OR DELETE ON notifications.push_delivery_attempt FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE notifications.push_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications.push_subscription FORCE ROW LEVEL SECURITY;
ALTER TABLE notifications.push_subscription_command_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications.push_subscription_command_idempotency FORCE ROW LEVEL SECURITY;
ALTER TABLE notifications.push_delivery_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications.push_delivery_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY v17_push_subscription_scope ON notifications.push_subscription
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));
CREATE POLICY v17_push_subscription_idempotency_scope ON notifications.push_subscription_command_idempotency
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));
CREATE POLICY v17_push_delivery_attempt_scope ON notifications.push_delivery_attempt
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON notifications.push_subscription,
            notifications.push_subscription_command_idempotency, notifications.push_delivery_attempt TO nexa_runtime;
    END IF;
END;
$$;
