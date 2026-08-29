-- Forward-only push delivery fencing. A claim is committed before provider I/O
-- so concurrent workers cannot invoke the same subscription/event together.

CREATE TABLE notifications.push_delivery_claim (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    event_id UUID NOT NULL,
    delivery_key VARCHAR(320) NOT NULL,
    status VARCHAR(16) NOT NULL,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_push_delivery_claim PRIMARY KEY (tenant_id, workspace_id, subscription_id, event_id),
    CONSTRAINT fk_push_delivery_claim_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_push_delivery_claim_subscription FOREIGN KEY (tenant_id, workspace_id, subscription_id)
        REFERENCES notifications.push_subscription (tenant_id, workspace_id, id),
    CONSTRAINT ck_push_delivery_claim_status CHECK (status IN ('CLAIMED','SENT')),
    CONSTRAINT ck_push_delivery_claim_key CHECK (length(btrim(delivery_key)) BETWEEN 1 AND 320),
    CONSTRAINT ck_push_delivery_claim_state CHECK (
        (status = 'CLAIMED' AND lease_until IS NOT NULL)
        OR (status = 'SENT' AND claim_token IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX ix_push_delivery_claim_lease
    ON notifications.push_delivery_claim (status, lease_until);

ALTER TABLE notifications.push_delivery_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications.push_delivery_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY v17_push_delivery_claim_scope ON notifications.push_delivery_claim
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON notifications.push_delivery_claim TO nexa_runtime;
    END IF;
END;
$$;
