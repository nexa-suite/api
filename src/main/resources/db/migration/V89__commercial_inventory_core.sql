-- v0.14 commercial and inventory core. V1-V88 are immutable.
-- Cross-context references deliberately use stable UUIDs without foreign keys
-- where ownership belongs to another bounded context.

ALTER TABLE sales.purchase_request
    DROP CONSTRAINT IF EXISTS ck_purchase_request_status,
    DROP CONSTRAINT IF EXISTS ck_purchase_request_payment_option,
    DROP CONSTRAINT IF EXISTS ck_purchase_request_payment_option_v1;

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_status_v2 CHECK (status IN (
        'DRAFT','SUBMITTED','IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED',
        'REJECTED','CANCELLED','CONVERTED_TO_ORDER','EXPIRED','WITHDRAWN'
    )),
    ADD CONSTRAINT ck_purchase_request_payment_option_v2 CHECK (payment_option IS NULL OR payment_option IN (
        'CREDIT_LINE','BANK_TRANSFER','CARD_STRIPE','CASH','CASH_ON_DELIVERY','PREPAID','IMMEDIATE'
    ));

ALTER TABLE sales.sales_order
    DROP CONSTRAINT IF EXISTS ck_sales_order_payment_option,
    DROP CONSTRAINT IF EXISTS ck_sales_order_payment_option_v2;

ALTER TABLE sales.sales_order
    ADD CONSTRAINT ck_sales_order_payment_option_v3 CHECK (payment_option IS NULL OR payment_option IN (
        'CREDIT_LINE','BANK_TRANSFER','CARD_STRIPE','CASH','CASH_ON_DELIVERY','PREPAID','IMMEDIATE'
    ));

ALTER TABLE sales.commercial_commitment
    ALTER COLUMN purchase_request_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS origin_type VARCHAR(24) NOT NULL DEFAULT 'PURCHASE_REQUEST';

ALTER TABLE sales.commercial_commitment_line
    ALTER COLUMN purchase_request_line_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS unit_price_amount NUMERIC(18,4) NOT NULL DEFAULT 0;

UPDATE sales.commercial_commitment_line line
SET unit_price_amount = coalesce(request_line.unit_price_amount, 0)
FROM sales.purchase_request_line request_line
WHERE request_line.id = line.purchase_request_line_id
  AND line.unit_price_amount = 0;

ALTER TABLE sales.commercial_commitment
    DROP CONSTRAINT IF EXISTS uq_commercial_commitment_request,
    DROP CONSTRAINT IF EXISTS ck_commercial_commitment_terminal_data,
    ADD CONSTRAINT ck_commercial_commitment_origin CHECK (
        (origin_type = 'PURCHASE_REQUEST' AND purchase_request_id IS NOT NULL)
        OR (origin_type = 'DIRECT_ORDER' AND purchase_request_id IS NULL)
    ),
    ADD CONSTRAINT ck_commercial_commitment_terminal_data_v2 CHECK (
        (status='ACTIVE' AND released_at IS NULL AND converted_at IS NULL AND sales_order_id IS NULL)
        OR (status IN ('RELEASED','EXPIRED','WITHDRAWN') AND released_at IS NOT NULL AND converted_at IS NULL AND sales_order_id IS NULL)
        OR (status='CONVERTED' AND converted_at IS NOT NULL AND sales_order_id IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_commercial_commitment_purchase_request_v2
    ON sales.commercial_commitment (tenant_id, workspace_id, purchase_request_id)
    WHERE purchase_request_id IS NOT NULL;

ALTER TABLE sales.sales_order
    ALTER COLUMN source_purchase_request_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS commercial_commitment_id UUID,
    ADD COLUMN IF NOT EXISTS origin_type VARCHAR(24) NOT NULL DEFAULT 'PURCHASE_REQUEST';

-- MANUAL is a pre-v0.14 legacy path. Preserve it explicitly; it is not a
-- Direct Order and must not be silently reclassified as one.
UPDATE sales.sales_order
   SET origin_type = CASE WHEN order_source = 'MANUAL' THEN 'MANUAL' ELSE 'PURCHASE_REQUEST' END
 WHERE origin_type = 'PURCHASE_REQUEST';

-- Upgrade existing v0.13 projections without manufacturing a new commitment.
-- Converted PR orders and credit reservations already have the authoritative
-- relationship through the v1 commitment / purchase request identifiers.
UPDATE sales.sales_order order_row
SET commercial_commitment_id = commitment.id
FROM sales.commercial_commitment commitment
WHERE commitment.tenant_id = order_row.tenant_id
  AND commitment.workspace_id = order_row.workspace_id
  AND commitment.sales_order_id = order_row.id
  AND order_row.commercial_commitment_id IS NULL;

ALTER TABLE sales.sales_order
    DROP CONSTRAINT IF EXISTS fk_sales_order_source_request,
    DROP CONSTRAINT IF EXISTS ck_sales_order_source,
    ADD CONSTRAINT fk_sales_order_source_request_v2
        FOREIGN KEY (tenant_id, workspace_id, source_purchase_request_id)
        REFERENCES sales.purchase_request (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_sales_order_commitment
        FOREIGN KEY (tenant_id, workspace_id, commercial_commitment_id)
        REFERENCES sales.commercial_commitment (tenant_id, workspace_id, id),
    ADD CONSTRAINT ck_sales_order_origin CHECK (
        (origin_type = 'PURCHASE_REQUEST' AND source_purchase_request_id IS NOT NULL)
        OR (origin_type = 'DIRECT_ORDER' AND source_purchase_request_id IS NULL AND commercial_commitment_id IS NOT NULL)
        OR (origin_type = 'MANUAL' AND source_purchase_request_id IS NULL AND commercial_commitment_id IS NULL)
    ),
    ADD CONSTRAINT ck_sales_order_source_v2 CHECK (
        (order_source = 'PURCHASE_REQUEST' AND origin_type = 'PURCHASE_REQUEST')
        OR (order_source = 'DIRECT_ORDER' AND origin_type = 'DIRECT_ORDER')
        OR (order_source = 'MANUAL' AND origin_type = 'MANUAL')
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_sales_order_commitment
    ON sales.sales_order (tenant_id, workspace_id, commercial_commitment_id)
    WHERE commercial_commitment_id IS NOT NULL;

ALTER TABLE payments.credit_reservation
    ADD COLUMN IF NOT EXISTS commercial_commitment_id UUID;

UPDATE payments.credit_reservation reservation
SET commercial_commitment_id = commitment.id
FROM sales.commercial_commitment commitment
WHERE commitment.tenant_id = reservation.tenant_id
  AND commitment.workspace_id = reservation.workspace_id
  AND commitment.purchase_request_id = reservation.purchase_request_id
  AND reservation.commercial_commitment_id IS NULL;

ALTER TABLE payments.credit_reservation
    ADD CONSTRAINT fk_credit_reservation_commitment
        FOREIGN KEY (tenant_id, workspace_id, commercial_commitment_id)
        REFERENCES sales.commercial_commitment (tenant_id, workspace_id, id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_credit_reservation_active_commitment
    ON payments.credit_reservation (tenant_id, workspace_id, commercial_commitment_id)
    WHERE commercial_commitment_id IS NOT NULL AND status='RESERVED';

CREATE INDEX IF NOT EXISTS ix_credit_reservation_commitment
    ON payments.credit_reservation (tenant_id, workspace_id, commercial_commitment_id, status);

CREATE OR REPLACE FUNCTION sales.prevent_sales_order_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
        OR NEW.number IS DISTINCT FROM OLD.number
        OR NEW.client_account_id IS DISTINCT FROM OLD.client_account_id
        OR NEW.created_by_membership_id IS DISTINCT FROM OLD.created_by_membership_id
        OR NEW.buyer_membership_id IS DISTINCT FROM OLD.buyer_membership_id
        OR NEW.source_purchase_request_id IS DISTINCT FROM OLD.source_purchase_request_id
        OR NEW.commercial_commitment_id IS DISTINCT FROM OLD.commercial_commitment_id
        OR NEW.origin_type IS DISTINCT FROM OLD.origin_type
        OR NEW.order_source IS DISTINCT FROM OLD.order_source
        OR NEW.priority IS DISTINCT FROM OLD.priority
        OR NEW.requested_delivery_date IS DISTINCT FROM OLD.requested_delivery_date
        OR NEW.delivery_snapshot IS DISTINCT FROM OLD.delivery_snapshot
        OR NEW.payment_option IS DISTINCT FROM OLD.payment_option
        OR NEW.notes IS DISTINCT FROM OLD.notes
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
    THEN
        RAISE EXCEPTION 'Sales order snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE warehouse.inventory_backing (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    commercial_commitment_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    release_reason VARCHAR(1000),
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inventory_backing_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_inventory_backing_commitment UNIQUE (tenant_id, workspace_id, commercial_commitment_id),
    CONSTRAINT fk_inventory_backing_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_inventory_backing_status CHECK (status IN ('REQUESTED','BACKED','RELEASED','CONSUMED','FAILED')),
    CONSTRAINT ck_inventory_backing_terminal_data CHECK (
        (status IN ('REQUESTED','FAILED') AND completed_at IS NULL AND released_at IS NULL)
        OR (status IN ('BACKED','CONSUMED') AND completed_at IS NOT NULL AND released_at IS NULL)
        OR (status='RELEASED' AND released_at IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE TABLE warehouse.inventory_backing_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    backing_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    requested_quantity NUMERIC(19,4) NOT NULL,
    backed_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    unit VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_backing_line_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_inventory_backing_line_sku UNIQUE (tenant_id, workspace_id, backing_id, sku_id),
    CONSTRAINT fk_inventory_backing_line_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_backing_line_backing FOREIGN KEY (tenant_id, workspace_id, backing_id)
        REFERENCES warehouse.inventory_backing (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_backing_line_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT ck_inventory_backing_line_quantities CHECK (
        requested_quantity > 0 AND backed_quantity >= 0 AND backed_quantity <= requested_quantity
    )
);

CREATE TABLE warehouse.inventory_backing_position (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    backing_line_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_backing_position_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_inventory_backing_position_warehouse UNIQUE (tenant_id, workspace_id, backing_line_id, warehouse_id),
    CONSTRAINT fk_inventory_backing_position_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_backing_position_line FOREIGN KEY (tenant_id, workspace_id, backing_line_id)
        REFERENCES warehouse.inventory_backing_line (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_backing_position_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT ck_inventory_backing_position_quantity CHECK (quantity > 0)
);

CREATE INDEX ix_inventory_backing_scope_status
    ON warehouse.inventory_backing (tenant_id, workspace_id, status, updated_at DESC, id);
CREATE INDEX ix_inventory_backing_line_sku
    ON warehouse.inventory_backing_line (tenant_id, workspace_id, sku_id, backing_id);
CREATE INDEX ix_inventory_backing_position_warehouse
    ON warehouse.inventory_backing_position (tenant_id, workspace_id, warehouse_id, backing_line_id);

DO $$
DECLARE
    target regclass;
    policy_name TEXT;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'warehouse.inventory_backing'::regclass,
        'warehouse.inventory_backing_line'::regclass,
        'warehouse.inventory_backing_position'::regclass
    ] LOOP
        policy_name := replace(target::text, '.', '_') || '_tenant_workspace_scope';
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
        EXECUTE format('CREATE POLICY %I ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))', policy_name, target);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON warehouse.inventory_backing,
            warehouse.inventory_backing_line, warehouse.inventory_backing_position TO nexa_runtime;
    END IF;
END $$;

-- Direct Orders are born confirmed only after the synchronous commercial,
-- inventory and credit decision succeeds. Keep the pre-confirmed row private
-- to the same transaction so a failed command leaves no business state.
COMMENT ON TABLE warehouse.inventory_backing IS
    'Inventory-owned availability backing for a warehouse-neutral commercial commitment; physical lot allocation remains downstream.';
