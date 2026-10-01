-- Dispatch handoff identity is separate from Buyer receipt tokens: it binds
-- only a prepared delivery, its current driver assignment and delivery version.
ALTER TABLE logistics.delivery_assignment
    ADD CONSTRAINT uq_delivery_assignment_handoff_binding
        UNIQUE (tenant_id, workspace_id, delivery_id, fulfillment_driver_assignment_id);

CREATE TABLE logistics.dispatch_handoff_identity (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    delivery_version BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    issuer_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_dispatch_handoff_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_dispatch_handoff_binding FOREIGN KEY
        (tenant_id, workspace_id, delivery_id, assignment_id)
        REFERENCES logistics.delivery_assignment
        (tenant_id, workspace_id, delivery_id, fulfillment_driver_assignment_id),
    CONSTRAINT uq_dispatch_handoff_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_dispatch_handoff_idempotency
        UNIQUE (tenant_id, workspace_id, issuer_membership_id, idempotency_key),
    CONSTRAINT ck_dispatch_handoff_version CHECK (delivery_version >= 0),
    CONSTRAINT ck_dispatch_handoff_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dispatch_handoff_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dispatch_handoff_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_dispatch_handoff_window CHECK (expires_at > issued_at),
    CONSTRAINT ck_dispatch_handoff_status CHECK (status IN ('ACTIVE', 'REPLACED'))
);

CREATE UNIQUE INDEX uq_dispatch_handoff_active_assignment
    ON logistics.dispatch_handoff_identity (tenant_id, workspace_id, assignment_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_dispatch_handoff_token_hash
    ON logistics.dispatch_handoff_identity (tenant_id, workspace_id, token_hash);

CREATE OR REPLACE FUNCTION logistics.prevent_dispatch_handoff_identity_mutation_v126()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
       OR NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.assignment_id IS DISTINCT FROM OLD.assignment_id
       OR NEW.delivery_version IS DISTINCT FROM OLD.delivery_version
       OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
       OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
       OR NEW.issuer_membership_id IS DISTINCT FROM OLD.issuer_membership_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR OLD.status <> 'ACTIVE' OR NEW.status <> 'REPLACED' THEN
        RAISE EXCEPTION 'Dispatch handoff identity binding is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER logistics_dispatch_handoff_identity_lifecycle_v126
    BEFORE UPDATE OR DELETE ON logistics.dispatch_handoff_identity FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_dispatch_handoff_identity_mutation_v126();

ALTER TABLE logistics.dispatch_handoff_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.dispatch_handoff_identity FORCE ROW LEVEL SECURITY;
CREATE POLICY v126_dispatch_handoff_identity_scope ON logistics.dispatch_handoff_identity
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON logistics.dispatch_handoff_identity TO nexa_runtime;
    END IF;
END;
$$;
