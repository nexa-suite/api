CREATE TABLE logistics.fulfillment_dispatch_window_plan (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    fulfillment_version BIGINT NOT NULL CHECK (fulfillment_version >= 0),
    revision INTEGER NOT NULL CHECK (revision >= 1),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    reason VARCHAR(2000) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    actor_membership_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_fulfillment_dispatch_window_revision UNIQUE (tenant_id, workspace_id, fulfillment_id, revision),
    CONSTRAINT uq_fulfillment_dispatch_window_id UNIQUE (tenant_id, workspace_id, fulfillment_id, id),
    CONSTRAINT uq_fulfillment_dispatch_window_key UNIQUE (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_fulfillment_dispatch_window_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_fulfillment_dispatch_window_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT fk_fulfillment_dispatch_window_actor FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_fulfillment_dispatch_window_order CHECK (window_start < window_end)
);

CREATE INDEX ix_fulfillment_dispatch_window_current
    ON logistics.fulfillment_dispatch_window_plan (tenant_id, workspace_id, fulfillment_id, revision DESC);

ALTER TABLE logistics.fulfillment_dispatch_window_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.fulfillment_dispatch_window_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY v138_fulfillment_dispatch_window_scope ON logistics.fulfillment_dispatch_window_plan
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));
CREATE TRIGGER fulfillment_dispatch_window_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_dispatch_window_plan
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON logistics.fulfillment_dispatch_window_plan TO nexa_runtime;
    END IF;
END $$;
