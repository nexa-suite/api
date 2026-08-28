-- v0.17 Mobile V1 core contracts. This migration is additive; V1-V92 remain
-- immutable and existing clients may continue using the legacy picking shape.

-- Existing V15 lifecycle values such as PARTIALLY_DELIVERED and
-- PARTIALLY_FULFILLED exceed the original VARCHAR(16) storage width. The
-- buyer-receipt flow reaches that existing state; widen storage without
-- changing the accepted status vocabulary or historical rows.
ALTER TABLE sales.sales_order
    ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE sales.sales_order_event
    ALTER COLUMN from_status TYPE VARCHAR(32),
    ALTER COLUMN to_status TYPE VARCHAR(32);

ALTER TABLE logistics.picking_result_line
    ADD COLUMN IF NOT EXISTS physical_allocation_line_id UUID,
    ADD COLUMN IF NOT EXISTS lot_id UUID,
    ADD COLUMN IF NOT EXISTS warehouse_id UUID;

CREATE INDEX ix_picking_result_line_physical_scan
    ON logistics.picking_result_line (tenant_id, workspace_id, physical_allocation_line_id, lot_id);

ALTER TABLE logistics.picking_result_line
    ADD CONSTRAINT ck_picking_result_line_physical_reference_v17 CHECK (
        (physical_allocation_line_id IS NULL AND lot_id IS NULL AND warehouse_id IS NULL)
        OR (physical_allocation_line_id IS NOT NULL AND lot_id IS NOT NULL AND warehouse_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_picking_result_line_physical_allocation_v17
        FOREIGN KEY (tenant_id, workspace_id, physical_allocation_line_id)
        REFERENCES warehouse.physical_allocation_line (tenant_id, workspace_id, id);

CREATE OR REPLACE FUNCTION logistics.prevent_picking_result_line_mutation_v17()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Picking result line is append-only';
END;
$$;
DROP TRIGGER IF EXISTS logistics_picking_result_line_append_only ON logistics.picking_result_line;
CREATE TRIGGER logistics_picking_result_line_append_only
    BEFORE UPDATE OR DELETE ON logistics.picking_result_line FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_picking_result_line_mutation_v17();

CREATE TABLE logistics.delivery_handoff_token (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    delivery_attempt_id UUID NOT NULL,
    customer_account_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    issuer_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_handoff_token_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_handoff_token_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT fk_handoff_token_attempt FOREIGN KEY (tenant_id, workspace_id, delivery_attempt_id, delivery_id)
        REFERENCES logistics.delivery_attempt (tenant_id, workspace_id, id, delivery_id),
    CONSTRAINT uq_handoff_token_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_handoff_token_idempotency UNIQUE (tenant_id, workspace_id, issuer_membership_id, idempotency_key),
    CONSTRAINT ck_handoff_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_handoff_token_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_handoff_token_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_handoff_token_window CHECK (expires_at > issued_at),
    CONSTRAINT ck_handoff_token_status CHECK (status IN ('ACTIVE','REPLACED','EXPIRED','CONSUMED'))
);

CREATE UNIQUE INDEX uq_handoff_token_active_delivery_attempt
    ON logistics.delivery_handoff_token (tenant_id, workspace_id, delivery_id, delivery_attempt_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_handoff_token_hash ON logistics.delivery_handoff_token (tenant_id, workspace_id, token_hash);

CREATE OR REPLACE FUNCTION logistics.prevent_delivery_handoff_token_mutation_v17()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id
       OR NEW.workspace_id <> OLD.workspace_id OR NEW.delivery_id <> OLD.delivery_id
       OR NEW.delivery_attempt_id <> OLD.delivery_attempt_id
       OR NEW.customer_account_id <> OLD.customer_account_id OR NEW.token_hash <> OLD.token_hash
       OR NEW.issued_at <> OLD.issued_at OR NEW.expires_at <> OLD.expires_at
       OR NEW.issuer_membership_id <> OLD.issuer_membership_id
       OR NEW.idempotency_key <> OLD.idempotency_key OR NEW.request_hash <> OLD.request_hash
       OR NEW.created_at <> OLD.created_at
       OR OLD.status <> 'ACTIVE' OR NEW.status NOT IN ('REPLACED','EXPIRED','CONSUMED') THEN
        RAISE EXCEPTION 'Delivery handoff token binding is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER logistics_delivery_handoff_token_lifecycle_v17
    BEFORE UPDATE OR DELETE ON logistics.delivery_handoff_token FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_delivery_handoff_token_mutation_v17();

CREATE TABLE logistics.buyer_receipt_fact (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    delivery_attempt_id UUID NOT NULL,
    customer_account_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    handoff_token_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL,
    driver_delivered_quantity NUMERIC(19,4) NOT NULL,
    accepted_quantity NUMERIC(19,4) NOT NULL,
    reason VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT fk_buyer_receipt_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_buyer_receipt_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT fk_buyer_receipt_attempt FOREIGN KEY (tenant_id, workspace_id, delivery_attempt_id, delivery_id)
        REFERENCES logistics.delivery_attempt (tenant_id, workspace_id, id, delivery_id),
    CONSTRAINT fk_buyer_receipt_handoff FOREIGN KEY (tenant_id, workspace_id, handoff_token_id)
        REFERENCES logistics.delivery_handoff_token (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_receipt_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_receipt_attempt UNIQUE (tenant_id, workspace_id, delivery_attempt_id),
    CONSTRAINT uq_buyer_receipt_idempotency UNIQUE (tenant_id, workspace_id, buyer_membership_id, idempotency_key),
    CONSTRAINT ck_buyer_receipt_decision CHECK (decision IN ('ACCEPTED','DISPUTED')),
    CONSTRAINT ck_buyer_receipt_quantities CHECK (driver_delivered_quantity >= 0 AND accepted_quantity >= 0 AND accepted_quantity <= driver_delivered_quantity),
    CONSTRAINT ck_buyer_receipt_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_buyer_receipt_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TRIGGER logistics_buyer_receipt_fact_append_only
    BEFORE UPDATE OR DELETE ON logistics.buyer_receipt_fact FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.delivery_handoff_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_handoff_token FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.buyer_receipt_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.buyer_receipt_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY v17_handoff_token_scope ON logistics.delivery_handoff_token
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));
CREATE POLICY v17_buyer_receipt_scope ON logistics.buyer_receipt_fact
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON logistics.delivery_handoff_token, logistics.buyer_receipt_fact TO nexa_runtime;
    END IF;
END;
$$;
