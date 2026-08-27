-- v0.15 Fulfillment & Financial Completion.
-- This migration is additive. V1-V90 remain immutable. Legacy reservation and
-- dispatch tables are retained as compatibility projections; the Target facts
-- below make physical responsibility and financial correction explicit.

-- Existing v0.14 rows are copied into the new projections below. The source
-- tables are FORCE RLS at runtime, while Flyway has no request scope; temporarily
-- remove FORCE for this transactional, owner-controlled backfill and restore it
-- before the migration commits.
ALTER TABLE logistics.dispatch_order NO FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.proof_of_delivery NO FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.receivable_allocation NO FORCE ROW LEVEL SECURITY;

-- v0.14 evidence was append-only, but v0.15 permits the explicit POD lifecycle
-- CAPTURED -> SEALED/REJECTED. Remove the old trigger before the historical
-- backfill; the immutable-evidence lifecycle trigger is installed below.
DROP TRIGGER IF EXISTS logistics_pod_append_only ON logistics.proof_of_delivery;

ALTER TABLE sales.sales_order
    DROP CONSTRAINT IF EXISTS ck_sales_order_status;

ALTER TABLE sales.sales_order
    ADD CONSTRAINT ck_sales_order_status_v15 CHECK (status IN (
        'PENDING','CONFIRMED','IN_FULFILLMENT','PARTIALLY_FULFILLED','FULFILLED',
        'PARTIALLY_DELIVERED','COMPLETED','REJECTED','CANCELLED'
    ));

ALTER TABLE payments.receivable
    ADD COLUMN IF NOT EXISTS adjustment_total NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS outstanding_amount NUMERIC(19,4)
        GENERATED ALWAYS AS (greatest(amount + adjustment_total - amount_paid, 0::numeric)) STORED,
    ADD COLUMN IF NOT EXISTS overpayment_amount NUMERIC(19,4)
        GENERATED ALWAYS AS (greatest(amount_paid - (amount + adjustment_total), 0::numeric)) STORED;

ALTER TABLE payments.receivable
    DROP CONSTRAINT IF EXISTS ck_receivable_amounts;

ALTER TABLE payments.receivable
    ADD CONSTRAINT ck_receivable_amounts_v15 CHECK (
        amount > 0 AND amount_paid >= 0 AND amount_paid <= amount
        AND amount + adjustment_total >= 0
    );

-- BC-07 keeps stable references to payment identities. Legacy projections stay
-- query-compatible but no longer impose a cross-context database FK.
ALTER TABLE payments.credit_reservation
    DROP CONSTRAINT IF EXISTS fk_credit_reservation_payment;
ALTER TABLE payments.receivable_allocation
    DROP CONSTRAINT IF EXISTS fk_receivable_allocation_payment;

CREATE TABLE warehouse.physical_allocation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    sales_order_id UUID NOT NULL,
    commercial_commitment_id UUID NOT NULL,
    inventory_backing_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ALLOCATED',
    allocated_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    release_reason VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    actor_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_physical_allocation_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_physical_allocation_backing UNIQUE (tenant_id, workspace_id, inventory_backing_id),
    CONSTRAINT uq_physical_allocation_fulfillment UNIQUE (tenant_id, workspace_id, fulfillment_id),
    CONSTRAINT uq_physical_allocation_command UNIQUE (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_physical_allocation_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_physical_allocation_backing FOREIGN KEY (tenant_id, workspace_id, inventory_backing_id)
        REFERENCES warehouse.inventory_backing (tenant_id, workspace_id, id),
    CONSTRAINT ck_physical_allocation_status CHECK (status IN ('ALLOCATED','RELEASED','CONSUMED')),
    CONSTRAINT ck_physical_allocation_command_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_physical_allocation_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_physical_allocation_version CHECK (version >= 0),
    CONSTRAINT ck_physical_allocation_terminal CHECK (
        (status = 'ALLOCATED' AND released_at IS NULL AND consumed_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND consumed_at IS NULL)
        OR (status = 'CONSUMED' AND released_at IS NULL AND consumed_at IS NOT NULL)
    )
);

CREATE TABLE warehouse.physical_allocation_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    physical_allocation_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    warehouse_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    released_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    consumed_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    unit VARCHAR(32) NOT NULL,
    expiration_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_physical_allocation_line_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_physical_allocation_line_lot UNIQUE (tenant_id, workspace_id, physical_allocation_id, lot_id),
    CONSTRAINT fk_physical_allocation_line_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_physical_allocation_line_parent FOREIGN KEY (tenant_id, workspace_id, physical_allocation_id)
        REFERENCES warehouse.physical_allocation (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_physical_allocation_line_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT fk_physical_allocation_line_zone FOREIGN KEY (tenant_id, workspace_id, warehouse_id, zone_id)
        REFERENCES warehouse.storage_zone (tenant_id, workspace_id, warehouse_id, id),
    CONSTRAINT fk_physical_allocation_line_lot FOREIGN KEY (tenant_id, workspace_id, warehouse_id, zone_id, lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, zone_id, id),
    CONSTRAINT ck_physical_allocation_line_quantity CHECK (
        quantity > 0 AND released_quantity >= 0 AND consumed_quantity >= 0
        AND released_quantity + consumed_quantity <= quantity
    ),
    CONSTRAINT ck_physical_allocation_line_text CHECK (length(btrim(catalog_item_id)) BETWEEN 1 AND 64 AND length(btrim(unit)) BETWEEN 1 AND 32)
);

CREATE TABLE warehouse.physical_allocation_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    physical_allocation_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_membership_id UUID NOT NULL,
    reason VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_physical_allocation_event_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_physical_allocation_event_parent FOREIGN KEY (tenant_id, workspace_id, physical_allocation_id)
        REFERENCES warehouse.physical_allocation (tenant_id, workspace_id, id)
);

CREATE INDEX ix_physical_allocation_order_status
    ON warehouse.physical_allocation (tenant_id, workspace_id, sales_order_id, status, updated_at DESC, id);
CREATE INDEX ix_physical_allocation_line_sku_warehouse_lot
    ON warehouse.physical_allocation_line (tenant_id, workspace_id, sku_id, warehouse_id, lot_id, id);
CREATE INDEX ix_physical_allocation_event_time
    ON warehouse.physical_allocation_event (tenant_id, workspace_id, physical_allocation_id, occurred_at, id);

CREATE TRIGGER warehouse_physical_allocation_event_append_only
    BEFORE UPDATE OR DELETE ON warehouse.physical_allocation_event FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE warehouse.physical_allocation_command_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_physical_allocation_command_idempotency PRIMARY KEY (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key),
    CONSTRAINT ck_physical_allocation_command_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_physical_allocation_command_idempotency_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT fk_physical_allocation_command_idempotency_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id)
);

CREATE INDEX ix_physical_allocation_command_idempotency_created
    ON warehouse.physical_allocation_command_idempotency (tenant_id, workspace_id, created_at DESC);

CREATE TRIGGER warehouse_physical_allocation_command_idempotency_append_only
    BEFORE UPDATE OR DELETE ON warehouse.physical_allocation_command_idempotency FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE logistics.fulfillment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    sales_order_id UUID NOT NULL,
    physical_allocation_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    destination_snapshot TEXT,
    planned_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    allocated_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    picked_at TIMESTAMPTZ,
    packed_at TIMESTAMPTZ,
    staged_at TIMESTAMPTZ,
    dispatched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    actor_membership_id UUID NOT NULL,
    CONSTRAINT uq_fulfillment_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_fulfillment_allocation UNIQUE (tenant_id, workspace_id, physical_allocation_id),
    CONSTRAINT fk_fulfillment_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_fulfillment_status CHECK (status IN (
        'PLANNED','ALLOCATED','PICKING','PICKED','PACKED','STAGED',
        'READY_FOR_DISPATCH','HANDED_OVER','COMPLETED','SHORTAGE','HOLD','CANCELLED'
    )),
    CONSTRAINT ck_fulfillment_version CHECK (version >= 0)
);

CREATE TABLE logistics.fulfillment_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    physical_allocation_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    ordered_quantity NUMERIC(19,4) NOT NULL,
    planned_quantity NUMERIC(19,4) GENERATED ALWAYS AS (ordered_quantity) STORED,
    backed_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    allocated_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    picked_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    packed_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    staged_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    dispatched_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    delivered_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    rejected_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    cancelled_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    unfulfilled_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    unit VARCHAR(32) NOT NULL,
    remaining_quantity NUMERIC(19,4)
        GENERATED ALWAYS AS (greatest(ordered_quantity - unfulfilled_quantity - delivered_quantity - rejected_quantity - cancelled_quantity, 0::numeric)) STORED,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fulfillment_line_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_fulfillment_line_item UNIQUE (tenant_id, workspace_id, fulfillment_id, catalog_item_id),
    CONSTRAINT fk_fulfillment_line_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_fulfillment_line_parent FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_fulfillment_line_quantity CHECK (
        ordered_quantity > 0 AND backed_quantity BETWEEN 0 AND ordered_quantity
        AND allocated_quantity BETWEEN 0 AND backed_quantity
        AND picked_quantity BETWEEN 0 AND allocated_quantity
        AND packed_quantity BETWEEN 0 AND picked_quantity
        AND staged_quantity BETWEEN 0 AND packed_quantity
        AND dispatched_quantity BETWEEN 0 AND staged_quantity
        AND delivered_quantity BETWEEN 0 AND dispatched_quantity
        AND rejected_quantity >= 0 AND cancelled_quantity >= 0
        AND unfulfilled_quantity >= 0 AND unfulfilled_quantity <= allocated_quantity - picked_quantity
        AND delivered_quantity + rejected_quantity + cancelled_quantity <= dispatched_quantity
        AND delivered_quantity + rejected_quantity + cancelled_quantity + unfulfilled_quantity <= ordered_quantity
    ),
    CONSTRAINT ck_fulfillment_line_text CHECK (length(btrim(catalog_item_id)) BETWEEN 1 AND 64 AND length(btrim(unit)) BETWEEN 1 AND 32)
);

CREATE TABLE logistics.fulfillment_command_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_fulfillment_command_idempotency PRIMARY KEY (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key),
    CONSTRAINT ck_fulfillment_command_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fulfillment_command_idempotency_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT fk_fulfillment_command_idempotency_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id)
);

CREATE INDEX ix_fulfillment_command_idempotency_created
    ON logistics.fulfillment_command_idempotency (tenant_id, workspace_id, created_at DESC);

CREATE TRIGGER logistics_fulfillment_command_idempotency_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_command_idempotency FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE logistics.fulfillment_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_membership_id UUID NOT NULL,
    reason VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_fulfillment_event_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_fulfillment_event_parent FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id)
);

CREATE TABLE logistics.picking_result (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    actor_membership_id UUID NOT NULL,
    picker_identity_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_picking_result_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_picking_result_fulfillment UNIQUE (tenant_id, workspace_id, fulfillment_id),
    CONSTRAINT uq_picking_result_command UNIQUE (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_picking_result_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_picking_result_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT ck_picking_result_status CHECK (status IN ('STARTED','CONFIRMED','DISCREPANCY')),
    CONSTRAINT ck_picking_result_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE logistics.picking_result_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    picking_result_id UUID NOT NULL,
    fulfillment_line_id UUID NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_picking_result_line_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_picking_result_line_parent FOREIGN KEY (tenant_id, workspace_id, picking_result_id)
        REFERENCES logistics.picking_result (tenant_id, workspace_id, id),
    CONSTRAINT fk_picking_result_line_fulfillment_line FOREIGN KEY (tenant_id, workspace_id, fulfillment_line_id)
        REFERENCES logistics.fulfillment_line (tenant_id, workspace_id, id),
    CONSTRAINT uq_picking_result_line UNIQUE (tenant_id, workspace_id, picking_result_id, fulfillment_line_id),
    CONSTRAINT ck_picking_result_line_quantity CHECK (quantity > 0)
);

CREATE TABLE logistics.picking_discrepancy (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    fulfillment_line_id UUID NOT NULL,
    picking_result_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    resolution VARCHAR(500),
    resolved_at TIMESTAMPTZ,
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_picking_discrepancy_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_picking_discrepancy_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT fk_picking_discrepancy_line FOREIGN KEY (tenant_id, workspace_id, fulfillment_line_id)
        REFERENCES logistics.fulfillment_line (tenant_id, workspace_id, id),
    CONSTRAINT fk_picking_discrepancy_result FOREIGN KEY (tenant_id, workspace_id, picking_result_id)
        REFERENCES logistics.picking_result (tenant_id, workspace_id, id),
    CONSTRAINT uq_picking_discrepancy_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT ck_picking_discrepancy_kind CHECK (kind IN ('SHORT','DAMAGED','WRONG_LOT','OTHER','SHORTAGE','DAMAGE','EXCESS')),
    CONSTRAINT ck_picking_discrepancy_quantity CHECK (quantity > 0)
);

-- Discrepancies are immutable evidence. Resolution is a separate append-only
-- fact so the original picker observation is never overwritten.
CREATE TABLE logistics.picking_discrepancy_resolution (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    discrepancy_id UUID NOT NULL,
    resolution_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    fulfillment_line_id UUID NOT NULL,
    resolution_type VARCHAR(32) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    actor_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_picking_discrepancy_resolution_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_picking_discrepancy_resolution_discrepancy UNIQUE (tenant_id, workspace_id, discrepancy_id),
    CONSTRAINT uq_picking_discrepancy_resolution_command UNIQUE (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_picking_discrepancy_resolution_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_picking_discrepancy_resolution_discrepancy FOREIGN KEY (tenant_id, workspace_id, discrepancy_id)
        REFERENCES logistics.picking_discrepancy (tenant_id, workspace_id, id),
    CONSTRAINT fk_picking_discrepancy_resolution_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT fk_picking_discrepancy_resolution_line FOREIGN KEY (tenant_id, workspace_id, fulfillment_line_id)
        REFERENCES logistics.fulfillment_line (tenant_id, workspace_id, id),
    CONSTRAINT ck_picking_discrepancy_resolution_type CHECK (resolution_type IN ('FINAL_UNFULFILLED')),
    CONSTRAINT ck_picking_discrepancy_resolution_quantity CHECK (quantity > 0),
    CONSTRAINT ck_picking_discrepancy_resolution_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_picking_discrepancy_resolution_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160)
);

CREATE INDEX ix_fulfillment_order_status ON logistics.fulfillment (tenant_id, workspace_id, sales_order_id, status, updated_at DESC, id);
CREATE INDEX ix_fulfillment_line_parent ON logistics.fulfillment_line (tenant_id, workspace_id, fulfillment_id, catalog_item_id);
CREATE INDEX ix_fulfillment_event_time ON logistics.fulfillment_event (tenant_id, workspace_id, fulfillment_id, occurred_at, id);
CREATE INDEX ix_picking_discrepancy_fulfillment ON logistics.picking_discrepancy (tenant_id, workspace_id, fulfillment_id, created_at, id);
CREATE INDEX ix_picking_discrepancy_resolution_batch ON logistics.picking_discrepancy_resolution (tenant_id, workspace_id, resolution_id, created_at, id);

CREATE TRIGGER logistics_fulfillment_event_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_event FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

-- Picking result is immutable evidence with one explicit lifecycle update. The
-- v0.14 generic append-only trigger would reject the confirmation itself.
DROP TRIGGER IF EXISTS logistics_picking_result_append_only ON logistics.picking_result;
CREATE OR REPLACE FUNCTION logistics.prevent_picking_result_mutation_v15()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Picking result is append-only';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.id <> OLD.id OR NEW.fulfillment_id <> OLD.fulfillment_id
       OR NEW.actor_membership_id <> OLD.actor_membership_id
       OR NEW.picker_identity_id <> OLD.picker_identity_id
       OR NEW.started_at <> OLD.started_at
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.request_hash <> OLD.request_hash
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Picking result evidence is immutable';
    END IF;
    IF OLD.status <> 'STARTED' OR NEW.status NOT IN ('CONFIRMED','DISCREPANCY') THEN
        RAISE EXCEPTION 'Picking result transition is invalid';
    END IF;
    IF NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'Completed picking result requires completed_at';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER logistics_picking_result_lifecycle_v15
    BEFORE UPDATE OR DELETE ON logistics.picking_result FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_picking_result_mutation_v15();
CREATE TRIGGER logistics_picking_result_line_append_only
    BEFORE UPDATE OR DELETE ON logistics.picking_result_line FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_picking_discrepancy_append_only
    BEFORE UPDATE OR DELETE ON logistics.picking_discrepancy FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_picking_discrepancy_resolution_append_only
    BEFORE UPDATE OR DELETE ON logistics.picking_discrepancy_resolution FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE logistics.delivery (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID,
    dispatch_order_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    destination_snapshot TEXT,
    scheduled_at TIMESTAMPTZ,
    dispatched_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_delivery_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_delivery_fulfillment UNIQUE (tenant_id, workspace_id, fulfillment_id),
    CONSTRAINT fk_delivery_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_delivery_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_dispatch FOREIGN KEY (tenant_id, workspace_id, dispatch_order_id)
        REFERENCES logistics.dispatch_order (tenant_id, workspace_id, id),
    CONSTRAINT ck_delivery_status CHECK (status IN ('PLANNED','ASSIGNED','DISPATCHED','IN_TRANSIT','PARTIAL','DELIVERED','FAILED','CANCELLED')),
    CONSTRAINT ck_delivery_version CHECK (version >= 0),
    CONSTRAINT ck_delivery_parent CHECK (fulfillment_id IS NOT NULL OR dispatch_order_id IS NOT NULL)
);

CREATE TABLE logistics.delivery_command_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_delivery_command_idempotency PRIMARY KEY (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key),
    CONSTRAINT ck_delivery_command_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_delivery_command_idempotency_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT fk_delivery_command_idempotency_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id)
);

CREATE INDEX ix_delivery_command_idempotency_created
    ON logistics.delivery_command_idempotency (tenant_id, workspace_id, created_at DESC);

CREATE TRIGGER logistics_delivery_command_idempotency_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_command_idempotency FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

INSERT INTO logistics.delivery(id, tenant_id, workspace_id, dispatch_order_id, status, destination_snapshot, created_at, updated_at, version)
SELECT d.id, d.tenant_id, d.workspace_id, d.id,
       CASE WHEN d.status = 'DELIVERED' THEN 'DELIVERED' WHEN d.status = 'CANCELLED' THEN 'CANCELLED' WHEN d.status = 'PARTIAL' THEN 'PARTIAL' WHEN d.status = 'IN_ROUTE' THEN 'IN_TRANSIT' WHEN d.status IN ('ASSIGNED') THEN 'ASSIGNED' WHEN d.status IN ('SCHEDULED','READY_FOR_ROUTE') THEN 'DISPATCHED' ELSE 'PLANNED' END,
       d.destination_snapshot, d.created_at, d.updated_at, d.version
FROM logistics.dispatch_order d
ON CONFLICT (tenant_id, workspace_id, id) DO NOTHING;

ALTER TABLE logistics.delivery_attempt
    DROP CONSTRAINT IF EXISTS fk_delivery_attempt_delivery,
    DROP CONSTRAINT IF EXISTS ck_delivery_attempt_status;

ALTER TABLE logistics.delivery_attempt
    ADD CONSTRAINT fk_delivery_attempt_delivery_v15 FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    ADD COLUMN IF NOT EXISTS outcome VARCHAR(16),
    ADD COLUMN IF NOT EXISTS attempted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_delivery_attempt_status_v15 CHECK (status IN ('FAILED','PARTIAL','FINAL','REJECTED')),
    ADD CONSTRAINT ck_delivery_attempt_outcome CHECK (outcome IS NULL OR outcome IN ('PENDING','DELIVERED','PARTIAL','FAILED','REFUSED','ABSENT','REJECTED','UNDELIVERED'));

ALTER TABLE logistics.delivery_attempt_line
    ADD COLUMN IF NOT EXISTS fulfillment_line_id UUID,
    ADD COLUMN IF NOT EXISTS sku_id UUID,
    ADD COLUMN IF NOT EXISTS attempted_quantity NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS received_quantity NUMERIC(19,4);

ALTER TABLE logistics.delivery_attempt_line
    ADD CONSTRAINT ck_delivery_attempt_line_v15_quantity CHECK (
        attempted_quantity IS NULL OR attempted_quantity > 0
    ),
    ADD CONSTRAINT ck_delivery_attempt_line_v15_received CHECK (
        received_quantity IS NULL OR (
            received_quantity >= 0
            AND attempted_quantity IS NOT NULL
            AND received_quantity <= attempted_quantity
        )
    ),
    ADD CONSTRAINT fk_delivery_attempt_line_fulfillment_line_v15
        FOREIGN KEY (tenant_id, workspace_id, fulfillment_line_id)
        REFERENCES logistics.fulfillment_line (tenant_id, workspace_id, id);

ALTER TABLE logistics.delivery_attempt
    ADD CONSTRAINT uq_delivery_attempt_scope_delivery_v15 UNIQUE (tenant_id, workspace_id, id, delivery_id);

CREATE TABLE logistics.delivery_assignment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    responsible_membership_id UUID NOT NULL,
    operator_id UUID NOT NULL,
    vehicle_reference VARCHAR(120),
    route_name VARCHAR(160),
    assigned_at TIMESTAMPTZ NOT NULL,
    actor_membership_id UUID NOT NULL,
    CONSTRAINT fk_delivery_assignment_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_delivery_assignment_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT uq_delivery_assignment_delivery UNIQUE (tenant_id, workspace_id, delivery_id)
);

CREATE TABLE logistics.delivery_quantity_outcome (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    delivery_attempt_id UUID NOT NULL,
    fulfillment_line_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    fulfilled_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    short_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    unit_price_amount NUMERIC(19,4),
    currency CHAR(3),
    reason VARCHAR(2000),
    final_resolution BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    actor_membership_id UUID NOT NULL,
    CONSTRAINT fk_delivery_outcome_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_delivery_outcome_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_outcome_attempt FOREIGN KEY (tenant_id, workspace_id, delivery_attempt_id)
        REFERENCES logistics.delivery_attempt (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_outcome_attempt_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_attempt_id, delivery_id)
        REFERENCES logistics.delivery_attempt (tenant_id, workspace_id, id, delivery_id),
    CONSTRAINT fk_delivery_outcome_line FOREIGN KEY (tenant_id, workspace_id, fulfillment_line_id)
        REFERENCES logistics.fulfillment_line (tenant_id, workspace_id, id),
    CONSTRAINT uq_delivery_outcome_attempt_sku UNIQUE (tenant_id, workspace_id, delivery_attempt_id, sku_id),
    CONSTRAINT uq_delivery_outcome_attempt_line UNIQUE (tenant_id, workspace_id, delivery_attempt_id, fulfillment_line_id),
    CONSTRAINT ck_delivery_outcome_kind CHECK (outcome IN ('DELIVERED','PARTIAL','REJECTED','UNDELIVERED')),
    CONSTRAINT ck_delivery_outcome_quantity CHECK (quantity > 0 AND fulfilled_quantity >= 0 AND short_quantity >= 0 AND fulfilled_quantity + short_quantity = quantity)
    ,CONSTRAINT ck_delivery_outcome_price CHECK (unit_price_amount IS NULL OR unit_price_amount >= 0)
    ,CONSTRAINT ck_delivery_outcome_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$')
);

CREATE TABLE logistics.delivery_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_membership_id UUID NOT NULL,
    reason VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_delivery_event_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_delivery_event_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id)
);

CREATE TABLE logistics.temperature_evidence (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    lot_id UUID,
    value NUMERIC(9,4),
    temperature_celsius NUMERIC(8,3),
    unit VARCHAR(16) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'MANUAL',
    evidence_metadata TEXT,
    status VARCHAR(16) NOT NULL,
    evidence_object_id UUID,
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_temperature_evidence_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_temperature_evidence_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT uq_temperature_evidence_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT ck_temperature_evidence_status CHECK (status IN ('WITHIN_RANGE','OUT_OF_RANGE','UNKNOWN')),
    CONSTRAINT ck_temperature_evidence_value CHECK (value IS NULL OR value > -1000 AND value < 1000)
);

CREATE TABLE logistics.temperature_excursion (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    lot_id UUID,
    temperature_evidence_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    disposition VARCHAR(24) NOT NULL DEFAULT 'HOLD',
    affected_quantity NUMERIC(19,4),
    threshold VARCHAR(160),
    reason VARCHAR(2000) NOT NULL,
    resolved_at TIMESTAMPTZ,
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_temperature_excursion_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_temperature_excursion_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT fk_temperature_excursion_evidence FOREIGN KEY (tenant_id, workspace_id, temperature_evidence_id)
        REFERENCES logistics.temperature_evidence (tenant_id, workspace_id, id),
    CONSTRAINT ck_temperature_excursion_status CHECK (status IN ('OPEN','HOLD','DISPOSITIONED','CLOSED','RESOLVED')),
    CONSTRAINT ck_temperature_excursion_disposition CHECK (disposition IN ('RELEASE','HOLD','WASTE','RETURN_TO_SUPPLIER'))
);

ALTER TABLE logistics.proof_of_delivery
    ALTER COLUMN dispatch_order_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS delivery_id UUID,
    ADD COLUMN IF NOT EXISTS attempt_id UUID,
    ADD COLUMN IF NOT EXISTS photo_evidence_object_id UUID,
    ADD COLUMN IF NOT EXISTS signature_evidence_object_id UUID,
    ADD COLUMN IF NOT EXISTS photo_object_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS signature_object_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS sealed_at TIMESTAMPTZ;

ALTER TABLE logistics.proof_of_delivery
    DROP CONSTRAINT IF EXISTS ck_pod_status,
    ADD CONSTRAINT ck_pod_status_v15 CHECK (status IN ('PENDING','COMPLETED','CAPTURED','SEALED','REJECTED'));

UPDATE logistics.proof_of_delivery
SET delivery_id = dispatch_order_id
WHERE delivery_id IS NULL;

ALTER TABLE logistics.proof_of_delivery
    ADD CONSTRAINT fk_pod_delivery_v15 FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    ADD CONSTRAINT uq_pod_scope_id_v15 UNIQUE (tenant_id, workspace_id, id),
    ADD CONSTRAINT ck_pod_parent_v15 CHECK (dispatch_order_id IS NOT NULL OR delivery_id IS NOT NULL);

CREATE UNIQUE INDEX uq_pod_delivery_v15
    ON logistics.proof_of_delivery (tenant_id, workspace_id, delivery_id)
    WHERE delivery_id IS NOT NULL;

CREATE TABLE logistics.proof_of_delivery_addendum (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    pod_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    evidence_object_id UUID,
    evidence_object_key VARCHAR(500),
    appended_by_membership_id UUID NOT NULL,
    appended_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_pod_addendum_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_pod_addendum_pod FOREIGN KEY (tenant_id, workspace_id, pod_id)
        REFERENCES logistics.proof_of_delivery (tenant_id, workspace_id, id),
    CONSTRAINT ck_pod_addendum_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500)
);

CREATE INDEX ix_pod_addendum_pod_time
    ON logistics.proof_of_delivery_addendum (tenant_id, workspace_id, pod_id, appended_at, id);

CREATE TRIGGER logistics_pod_addendum_append_only
    BEFORE UPDATE OR DELETE ON logistics.proof_of_delivery_addendum FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.continuation_delivery
    -- V0.14 called the terminal continuation state FULFILLED. Preserve those
    -- facts while aligning the v0.15 vocabulary before adding the new check.
    DROP CONSTRAINT IF EXISTS ck_continuation_delivery_status;

UPDATE logistics.continuation_delivery
SET status = 'COMPLETED'
WHERE status = 'FULFILLED';

ALTER TABLE logistics.continuation_delivery
    ALTER COLUMN source_delivery_id DROP NOT NULL,
    DROP CONSTRAINT IF EXISTS fk_continuation_delivery_source,
    ADD COLUMN IF NOT EXISTS parent_delivery_id UUID,
    ADD COLUMN IF NOT EXISTS remaining_snapshot TEXT,
    ADD COLUMN IF NOT EXISTS opened_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS request_hash CHAR(64);

ALTER TABLE logistics.continuation_delivery
    ADD CONSTRAINT fk_continuation_delivery_parent_v15 FOREIGN KEY (tenant_id, workspace_id, parent_delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    ADD CONSTRAINT ck_continuation_delivery_parent_v15 CHECK (source_delivery_id IS NOT NULL OR parent_delivery_id IS NOT NULL),
    ADD CONSTRAINT ck_continuation_delivery_hash_v15 CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE logistics.continuation_delivery
    DROP CONSTRAINT IF EXISTS ck_continuation_delivery_status,
    ADD CONSTRAINT ck_continuation_delivery_status_v15 CHECK (status IN ('OPEN','DISPATCHED','COMPLETED','CANCELLED'));

CREATE UNIQUE INDEX uq_continuation_delivery_parent_v15
    ON logistics.continuation_delivery (tenant_id, workspace_id, parent_delivery_id)
    WHERE parent_delivery_id IS NOT NULL;

ALTER TABLE logistics.continuation_delivery_line
    ADD COLUMN IF NOT EXISTS fulfillment_line_id UUID,
    ADD COLUMN IF NOT EXISTS sku_id UUID;

ALTER TABLE logistics.continuation_delivery_line
    ADD CONSTRAINT fk_continuation_delivery_line_fulfillment_line_v15
        FOREIGN KEY (tenant_id, workspace_id, fulfillment_line_id)
        REFERENCES logistics.fulfillment_line (tenant_id, workspace_id, id);

CREATE INDEX ix_delivery_fulfillment_status ON logistics.delivery (tenant_id, workspace_id, fulfillment_id, status, updated_at DESC, id);
CREATE INDEX ix_delivery_quantity_outcome_line ON logistics.delivery_quantity_outcome (tenant_id, workspace_id, fulfillment_line_id, created_at, id);
CREATE INDEX ix_delivery_event_time ON logistics.delivery_event (tenant_id, workspace_id, delivery_id, occurred_at, id);
CREATE INDEX ix_temperature_evidence_delivery_time ON logistics.temperature_evidence (tenant_id, workspace_id, delivery_id, recorded_at, id);
CREATE INDEX ix_temperature_excursion_open ON logistics.temperature_excursion (tenant_id, workspace_id, status, created_at, id);

CREATE TRIGGER logistics_delivery_assignment_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_assignment FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_delivery_quantity_outcome_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_quantity_outcome FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_delivery_event_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_event FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_temperature_evidence_append_only
    BEFORE UPDATE OR DELETE ON logistics.temperature_evidence FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

-- An excursion is immutable evidence whose disposition is a controlled,
-- one-way lifecycle. Its evidence and scope fields remain append-only.
DROP TRIGGER IF EXISTS logistics_temperature_excursion_append_only ON logistics.temperature_excursion;
CREATE OR REPLACE FUNCTION logistics.prevent_temperature_excursion_mutation_v15()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Temperature excursion is append-only';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.id <> OLD.id OR NEW.delivery_id <> OLD.delivery_id
       OR NEW.lot_id IS DISTINCT FROM OLD.lot_id
       OR NEW.temperature_evidence_id <> OLD.temperature_evidence_id
       OR NEW.affected_quantity IS DISTINCT FROM OLD.affected_quantity
       OR NEW.threshold IS DISTINCT FROM OLD.threshold
       OR NEW.reason <> OLD.reason
       OR NEW.actor_membership_id <> OLD.actor_membership_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Temperature excursion evidence is immutable';
    END IF;
    IF OLD.status NOT IN ('OPEN','HOLD') OR NEW.status NOT IN ('DISPOSITIONED','CLOSED','RESOLVED') THEN
        RAISE EXCEPTION 'Temperature excursion transition is invalid';
    END IF;
    IF NEW.resolved_at IS NULL THEN
        RAISE EXCEPTION 'Resolved temperature excursion requires resolved_at';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER logistics_temperature_excursion_lifecycle_v15
    BEFORE UPDATE OR DELETE ON logistics.temperature_excursion FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_temperature_excursion_mutation_v15();

CREATE OR REPLACE FUNCTION logistics.prevent_non_final_pod_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.delivery_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM logistics.delivery d
            WHERE d.tenant_id = NEW.tenant_id AND d.workspace_id = NEW.workspace_id
              AND d.id = NEW.delivery_id AND d.status = 'DELIVERED'
        ) THEN
            RAISE EXCEPTION 'Proof of delivery is only allowed for final delivery';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM logistics.dispatch_order d
        WHERE d.tenant_id = NEW.tenant_id AND d.workspace_id = NEW.workspace_id
          AND d.id = NEW.dispatch_order_id AND d.status = 'DELIVERED'
    ) THEN
        RAISE EXCEPTION 'Proof of delivery is only allowed for final delivery';
    END IF;
    RETURN NEW;
END;
$$;

-- The v0.14 append-only trigger prevented the target POD lifecycle from
-- sealing a captured proof. Keep evidence immutable while allowing only the
-- explicit CAPTURED -> SEALED/REJECTED transition.
DROP TRIGGER IF EXISTS logistics_pod_append_only ON logistics.proof_of_delivery;
CREATE OR REPLACE FUNCTION logistics.prevent_pod_mutation_v15()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Proof of delivery is append-only';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.id <> OLD.id OR NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.dispatch_order_id IS DISTINCT FROM OLD.dispatch_order_id
       OR NEW.receiver_name <> OLD.receiver_name
       OR NEW.completed_at <> OLD.completed_at
       OR NEW.notes IS DISTINCT FROM OLD.notes
       OR NEW.photo_evidence_declared <> OLD.photo_evidence_declared
       OR NEW.signature_evidence_declared <> OLD.signature_evidence_declared
       OR NEW.photo_evidence_object_id IS DISTINCT FROM OLD.photo_evidence_object_id
       OR NEW.signature_evidence_object_id IS DISTINCT FROM OLD.signature_evidence_object_id
       OR NEW.photo_object_key IS DISTINCT FROM OLD.photo_object_key
       OR NEW.signature_object_key IS DISTINCT FROM OLD.signature_object_key
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Proof of delivery evidence is immutable';
    END IF;
    IF NOT (OLD.status = 'CAPTURED' AND NEW.status IN ('SEALED','REJECTED')) THEN
        RAISE EXCEPTION 'Proof of delivery transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER logistics_pod_lifecycle_v15
    BEFORE UPDATE OR DELETE ON logistics.proof_of_delivery FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_pod_mutation_v15();

CREATE TABLE payments.financial_adjustment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    receivable_id UUID NOT NULL,
    sales_order_id UUID,
    delivery_id UUID,
    adjustment_kind VARCHAR(16) NOT NULL,
    kind VARCHAR(32) GENERATED ALWAYS AS (
        CASE adjustment_kind
            WHEN 'DECREASE' THEN 'CREDIT_NOTE'
            WHEN 'INCREASE' THEN 'DEBIT_NOTE'
            ELSE adjustment_kind
        END
    ) STORED,
    effect VARCHAR(8) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    adjusted_amount NUMERIC(19,4) NOT NULL,
    outstanding_amount NUMERIC(19,4) NOT NULL,
    receivable_version BIGINT NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    actor_membership_id UUID NOT NULL,
    created_by_identity_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_financial_adjustment_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_financial_adjustment_command UNIQUE (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_financial_adjustment_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_financial_adjustment_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT ck_financial_adjustment_kind CHECK (adjustment_kind IN ('INCREASE','DECREASE','WRITE_OFF','CORRECTION')),
    CONSTRAINT ck_financial_adjustment_effect CHECK (effect IN ('INCREASE','DECREASE')),
    CONSTRAINT ck_financial_adjustment_amount CHECK (amount > 0),
    CONSTRAINT ck_financial_adjustment_balances CHECK (adjusted_amount >= 0 AND outstanding_amount >= 0 AND receivable_version >= 0),
    CONSTRAINT ck_financial_adjustment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_financial_adjustment_status CHECK (status IN ('PENDING','APPROVED','POSTED','REJECTED')),
    CONSTRAINT ck_financial_adjustment_command_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_financial_adjustment_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE payments.financial_ledger_entry (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    financial_adjustment_id UUID NOT NULL,
    receivable_id UUID NOT NULL,
    effect VARCHAR(8) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    delta_amount NUMERIC(19,4) NOT NULL,
    direction VARCHAR(16) GENERATED ALWAYS AS (CASE WHEN effect = 'INCREASE' THEN 'DEBIT' ELSE 'CREDIT' END) STORED,
    currency CHAR(3) NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_financial_ledger_adjustment UNIQUE (tenant_id, workspace_id, financial_adjustment_id),
    CONSTRAINT fk_financial_ledger_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_financial_ledger_adjustment FOREIGN KEY (tenant_id, workspace_id, financial_adjustment_id)
        REFERENCES payments.financial_adjustment (tenant_id, workspace_id, id),
    CONSTRAINT fk_financial_ledger_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT ck_financial_ledger_effect CHECK (effect IN ('INCREASE','DECREASE')),
    CONSTRAINT ck_financial_ledger_amount CHECK (amount > 0 AND ((effect = 'INCREASE' AND delta_amount = amount) OR (effect = 'DECREASE' AND delta_amount = -amount))),
    CONSTRAINT ck_financial_ledger_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE payments.refund_credit_obligation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    financial_adjustment_id UUID NOT NULL,
    receivable_id UUID NOT NULL,
    sales_order_id UUID,
    payment_id UUID,
    obligation_type VARCHAR(16) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    reason VARCHAR(2000) NOT NULL,
    provider_reference VARCHAR(160),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uq_refund_credit_adjustment UNIQUE (tenant_id, workspace_id, financial_adjustment_id),
    CONSTRAINT fk_refund_credit_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_refund_credit_adjustment FOREIGN KEY (tenant_id, workspace_id, financial_adjustment_id)
        REFERENCES payments.financial_adjustment (tenant_id, workspace_id, id),
    CONSTRAINT fk_refund_credit_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT ck_refund_credit_type CHECK (obligation_type IN ('REFUND','CUSTOMER_CREDIT')),
    CONSTRAINT ck_refund_credit_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_credit_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_refund_credit_status CHECK (status IN ('OPEN','EXECUTED','CANCELLED')),
    CONSTRAINT ck_refund_credit_resolution CHECK ((status = 'OPEN' AND resolved_at IS NULL) OR (status IN ('EXECUTED','CANCELLED') AND resolved_at IS NOT NULL))
);

CREATE TABLE payments.receivable_application (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    receivable_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    reversed_at TIMESTAMPTZ,
    CONSTRAINT uq_receivable_application_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_receivable_application_payment UNIQUE (tenant_id, workspace_id, receivable_id, payment_id),
    CONSTRAINT fk_receivable_application_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_receivable_application_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT ck_receivable_application_amount CHECK (amount > 0)
);

INSERT INTO payments.receivable_application(id, tenant_id, workspace_id, receivable_id, payment_id, amount, currency, applied_at)
SELECT a.id, a.tenant_id, a.workspace_id, a.receivable_id, a.payment_id, a.amount, r.currency, a.allocated_at
FROM payments.receivable_allocation a
JOIN payments.receivable r ON r.tenant_id = a.tenant_id AND r.workspace_id = a.workspace_id AND r.id = a.receivable_id
ON CONFLICT (tenant_id, workspace_id, receivable_id, payment_id) DO NOTHING;

CREATE INDEX ix_financial_adjustment_receivable ON payments.financial_adjustment (tenant_id, workspace_id, receivable_id, posted_at, id);
CREATE INDEX ix_financial_ledger_receivable ON payments.financial_ledger_entry (tenant_id, workspace_id, receivable_id, created_at, id);
CREATE INDEX ix_refund_credit_open ON payments.refund_credit_obligation (tenant_id, workspace_id, status, created_at, id);
CREATE INDEX ix_receivable_application_receivable ON payments.receivable_application (tenant_id, workspace_id, receivable_id, applied_at, id);

CREATE TRIGGER payments_financial_adjustment_append_only
    BEFORE UPDATE OR DELETE ON payments.financial_adjustment FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER payments_financial_ledger_append_only
    BEFORE UPDATE OR DELETE ON payments.financial_ledger_entry FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

-- Refund/customer-credit obligations are immutable business decisions with an
-- explicit execution lifecycle. Payment/provider execution remains BC-08.
CREATE OR REPLACE FUNCTION payments.prevent_refund_credit_obligation_mutation_v15()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Refund/credit obligation is append-only';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.id <> OLD.id OR NEW.financial_adjustment_id <> OLD.financial_adjustment_id
       OR NEW.receivable_id <> OLD.receivable_id OR NEW.sales_order_id IS DISTINCT FROM OLD.sales_order_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id OR NEW.obligation_type <> OLD.obligation_type
       OR NEW.amount <> OLD.amount OR NEW.currency <> OLD.currency OR NEW.reason <> OLD.reason
       OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference
       OR NEW.actor_membership_id <> OLD.actor_membership_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Refund/credit obligation evidence is immutable';
    END IF;
    IF OLD.status <> 'OPEN' OR NEW.status NOT IN ('EXECUTED','CANCELLED') OR NEW.resolved_at IS NULL THEN
        RAISE EXCEPTION 'Refund/credit obligation transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payments_refund_credit_obligation_lifecycle_v15
    BEFORE UPDATE OR DELETE ON payments.refund_credit_obligation FOR EACH ROW
    EXECUTE FUNCTION payments.prevent_refund_credit_obligation_mutation_v15();

-- A receivable application is immutable settlement history; only its explicit
-- reversal timestamp can be appended once.
CREATE OR REPLACE FUNCTION payments.prevent_receivable_application_mutation_v15()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Receivable application is append-only';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.id <> OLD.id OR NEW.receivable_id <> OLD.receivable_id
       OR NEW.payment_id <> OLD.payment_id OR NEW.amount <> OLD.amount
       OR NEW.currency <> OLD.currency OR NEW.applied_at <> OLD.applied_at
       OR (OLD.reversed_at IS NOT NULL AND NEW.reversed_at IS DISTINCT FROM OLD.reversed_at)
       OR (OLD.reversed_at IS NULL AND NEW.reversed_at IS NULL) THEN
        RAISE EXCEPTION 'Receivable application evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payments_receivable_application_lifecycle_v15
    BEFORE UPDATE OR DELETE ON payments.receivable_application FOR EACH ROW
    EXECUTE FUNCTION payments.prevent_receivable_application_mutation_v15();

DO $$
DECLARE
    target regclass;
    policy_name TEXT;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'warehouse.physical_allocation'::regclass,
        'warehouse.physical_allocation_line'::regclass,
        'warehouse.physical_allocation_event'::regclass,
        'warehouse.physical_allocation_command_idempotency'::regclass,
        'logistics.fulfillment'::regclass,
        'logistics.fulfillment_line'::regclass,
        'logistics.fulfillment_command_idempotency'::regclass,
        'logistics.delivery_command_idempotency'::regclass,
        'logistics.fulfillment_event'::regclass,
        'logistics.picking_result'::regclass,
        'logistics.picking_result_line'::regclass,
        'logistics.picking_discrepancy'::regclass,
        'logistics.picking_discrepancy_resolution'::regclass,
        'logistics.delivery'::regclass,
        'logistics.delivery_assignment'::regclass,
        'logistics.delivery_quantity_outcome'::regclass,
        'logistics.delivery_event'::regclass,
        'logistics.temperature_evidence'::regclass,
        'logistics.temperature_excursion'::regclass,
        'logistics.proof_of_delivery_addendum'::regclass,
        'payments.financial_adjustment'::regclass,
        'payments.financial_ledger_entry'::regclass,
        'payments.refund_credit_obligation'::regclass,
        'payments.receivable_application'::regclass
    ] LOOP
        -- PostgreSQL identifiers are limited to 63 bytes. Keep the table
        -- portion inspectable and add a deterministic suffix for uniqueness.
        policy_name := 'v15_tws_' || left(replace(target::text, '.', '_'), 24)
            || '_' || substr(md5(target::text), 1, 12);
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
        EXECUTE format('CREATE POLICY %I ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))', policy_name, target);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            warehouse.physical_allocation, warehouse.physical_allocation_line, warehouse.physical_allocation_event,
            warehouse.physical_allocation_command_idempotency,
            logistics.fulfillment, logistics.fulfillment_line, logistics.fulfillment_event,
            logistics.fulfillment_command_idempotency,
            logistics.delivery_command_idempotency,
            logistics.picking_result, logistics.picking_result_line, logistics.picking_discrepancy,
            logistics.picking_discrepancy_resolution,
            logistics.delivery, logistics.delivery_assignment, logistics.delivery_quantity_outcome,
            logistics.delivery_event, logistics.temperature_evidence, logistics.temperature_excursion,
            logistics.proof_of_delivery_addendum,
            payments.financial_adjustment, payments.financial_ledger_entry,
            payments.refund_credit_obligation, payments.receivable_application TO nexa_runtime;
    END IF;
END;
$$;

-- Restore the source-table enforcement after the scoped backfill. The new
-- tables above are forced by the preceding registry loop.
ALTER TABLE logistics.dispatch_order FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.proof_of_delivery FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.receivable_allocation FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT UPDATE ON logistics.delivery_attempt, logistics.proof_of_delivery, payments.receivable TO nexa_runtime;
    END IF;
END;
$$;
