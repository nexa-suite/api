CREATE TABLE IF NOT EXISTS sales.manual_sales_order_draft (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    created_by_membership_id UUID NOT NULL,
    client_account_id UUID,
    delivery_address_id UUID,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    requested_delivery_date DATE,
    payment_preference VARCHAR(40),
    currency CHAR(3) NOT NULL DEFAULT 'PEN',
    notes VARCHAR(2000),
    delivery_notes VARCHAR(2000),
    credit_result VARCHAR(32),
    client_snapshot JSONB,
    delivery_address_snapshot JSONB,
    route_snapshot JSONB,
    warehouse_id UUID,
    warehouse_selection_snapshot JSONB,
    sales_order_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    CONSTRAINT uq_manual_sales_order_draft_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_manual_sales_order_draft_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_manual_sales_order_draft_creator FOREIGN KEY (workspace_id, created_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_manual_sales_order_draft_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT fk_manual_sales_order_draft_address FOREIGN KEY (tenant_id, workspace_id, delivery_address_id)
        REFERENCES sales.client_account_address (tenant_id, workspace_id, id),
    CONSTRAINT fk_manual_sales_order_draft_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT fk_manual_sales_order_draft_order FOREIGN KEY (tenant_id, workspace_id, sales_order_id)
        REFERENCES sales.sales_order (tenant_id, workspace_id, id),
    CONSTRAINT ck_manual_sales_order_draft_status CHECK (status IN ('DRAFT','CLIENT_COMPLETE','ITEMS_COMPLETE','DELIVERY_COMPLETE','READY_TO_CREATE','CREATED','ABANDONED')),
    CONSTRAINT ck_manual_sales_order_draft_priority CHECK (priority IN ('NORMAL','HIGH','URGENT')),
    CONSTRAINT ck_manual_sales_order_draft_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_manual_sales_order_draft_created CHECK (status <> 'CREATED' OR (sales_order_id IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_manual_sales_order_draft_schema_size CHECK (
        coalesce(octet_length(client_snapshot::text), 0) <= 32000
        AND coalesce(octet_length(delivery_address_snapshot::text), 0) <= 32000
        AND coalesce(octet_length(route_snapshot::text), 0) <= 32000
        AND coalesce(octet_length(warehouse_selection_snapshot::text), 0) <= 32000
    )
);

CREATE TABLE IF NOT EXISTS sales.manual_sales_order_draft_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    draft_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    product_family_id UUID NOT NULL,
    product_family_code_snapshot VARCHAR(80) NOT NULL,
    product_family_name_snapshot VARCHAR(240) NOT NULL,
    sku_code_snapshot VARCHAR(80) NOT NULL,
    presentation_snapshot VARCHAR(240) NOT NULL,
    unit_of_measure VARCHAR(32) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    base_unit_price NUMERIC(19,4) NOT NULL,
    effective_unit_price NUMERIC(19,4) NOT NULL,
    discount_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    availability_status VARCHAR(20) NOT NULL,
    promotion_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    notes VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_manual_sales_order_draft_line UNIQUE (draft_id, sku_id),
    CONSTRAINT fk_manual_sales_order_draft_line_draft FOREIGN KEY (tenant_id, workspace_id, draft_id)
        REFERENCES sales.manual_sales_order_draft (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_manual_sales_order_draft_line_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT fk_manual_sales_order_draft_line_family FOREIGN KEY (tenant_id, workspace_id, product_family_id)
        REFERENCES catalog_management.product_family (tenant_id, workspace_id, id),
    CONSTRAINT ck_manual_sales_order_draft_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_manual_sales_order_draft_line_prices CHECK (base_unit_price >= 0 AND effective_unit_price >= 0 AND discount_amount >= 0),
    CONSTRAINT ck_manual_sales_order_draft_line_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_manual_sales_order_draft_line_availability CHECK (availability_status IN ('AVAILABLE','LIMITED','UNAVAILABLE')),
    CONSTRAINT ck_manual_sales_order_draft_line_promotions CHECK (octet_length(promotion_references::text) <= 16000)
);

CREATE TABLE IF NOT EXISTS sales.manual_sales_order_draft_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    draft_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_manual_sales_order_draft_idempotency_draft FOREIGN KEY (tenant_id, workspace_id, draft_id)
        REFERENCES sales.manual_sales_order_draft (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_manual_sales_order_draft_idempotency_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_manual_sales_order_draft_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS ix_manual_sales_order_draft_scope_status
    ON sales.manual_sales_order_draft (tenant_id, workspace_id, status, updated_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_manual_sales_order_draft_line_scope_sku
    ON sales.manual_sales_order_draft_line (tenant_id, workspace_id, sku_id, draft_id);

ALTER TABLE sales.manual_sales_order_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.manual_sales_order_draft_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.manual_sales_order_draft_idempotency ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS manual_sales_order_draft_tenant_workspace_scope ON sales.manual_sales_order_draft;
CREATE POLICY manual_sales_order_draft_tenant_workspace_scope ON sales.manual_sales_order_draft
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true));

DROP POLICY IF EXISTS manual_sales_order_draft_line_tenant_workspace_scope ON sales.manual_sales_order_draft_line;
CREATE POLICY manual_sales_order_draft_line_tenant_workspace_scope ON sales.manual_sales_order_draft_line
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true));

DROP POLICY IF EXISTS manual_sales_order_draft_idempotency_tenant_workspace_scope ON sales.manual_sales_order_draft_idempotency;
CREATE POLICY manual_sales_order_draft_idempotency_tenant_workspace_scope ON sales.manual_sales_order_draft_idempotency
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true));
