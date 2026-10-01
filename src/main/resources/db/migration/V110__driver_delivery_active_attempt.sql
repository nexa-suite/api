-- Current Delivery Attempt is distinct from the immutable terminal attempt fact.
-- One scoped row exists only while an attempt is active; final outcomes consume
-- its identity into logistics.delivery_attempt in the same transaction.
CREATE TABLE logistics.delivery_active_attempt (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_by_membership_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_delivery_active_attempt_scope_delivery UNIQUE (tenant_id, workspace_id, delivery_id),
    CONSTRAINT uq_delivery_active_attempt_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_active_attempt_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_delivery_active_attempt_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT ck_delivery_active_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_delivery_active_attempt_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_delivery_active_attempt_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_delivery_active_attempt_scope_actor
    ON logistics.delivery_active_attempt (tenant_id, workspace_id, started_by_membership_id, started_at DESC);

ALTER TABLE logistics.delivery_active_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_active_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY v110_delivery_active_attempt_scope ON logistics.delivery_active_attempt
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON logistics.delivery_active_attempt TO nexa_runtime;
    END IF;
END;
$$;
