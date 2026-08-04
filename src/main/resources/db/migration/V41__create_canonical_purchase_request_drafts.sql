ALTER TABLE sales.client_account_address
    ADD CONSTRAINT uq_client_account_address_scope_id UNIQUE (tenant_id, workspace_id, id);

CREATE TABLE IF NOT EXISTS sales.purchase_request_draft (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    payment_preference VARCHAR(40),
    credit_result VARCHAR(32),
    route_provider VARCHAR(40),
    requested_delivery_date DATE,
    version BIGINT NOT NULL DEFAULT 0,
    snapshot_schema_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_purchase_request_draft_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_purchase_request_draft_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_purchase_request_draft_buyer FOREIGN KEY (workspace_id, buyer_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_purchase_request_draft_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT ck_purchase_request_draft_status CHECK (status IN ('DRAFT','PRODUCTS_COMPLETE','DESTINATION_COMPLETE','ROUTE_VALIDATED','COMMERCIAL_REVIEW_COMPLETE','READY_TO_SUBMIT','SUBMITTED')),
    CONSTRAINT ck_purchase_request_draft_schema CHECK (snapshot_schema_version ~ '^[0-9]+\\.[0-9]+$'),
    CONSTRAINT ck_purchase_request_draft_submitted CHECK (status <> 'SUBMITTED' OR submitted_at IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS sales.purchase_request_draft_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    draft_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    sku_code_snapshot VARCHAR(80) NOT NULL,
    presentation_snapshot VARCHAR(160) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    base_unit_price NUMERIC(19,4) NOT NULL,
    effective_unit_price NUMERIC(19,4) NOT NULL,
    discount_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    promotion_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    notes VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_purchase_request_draft_line UNIQUE (draft_id, sku_id),
    CONSTRAINT fk_purchase_request_draft_line_draft FOREIGN KEY (tenant_id, workspace_id, draft_id)
        REFERENCES sales.purchase_request_draft (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_request_draft_line_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT ck_purchase_request_draft_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_purchase_request_draft_line_prices CHECK (base_unit_price >= 0 AND effective_unit_price >= 0 AND discount_amount >= 0),
    CONSTRAINT ck_purchase_request_draft_line_schema CHECK (octet_length(promotion_references::text) <= 16000)
);

CREATE TABLE IF NOT EXISTS sales.purchase_request_draft_destination (
    draft_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    address_id UUID NOT NULL,
    address_snapshot JSONB NOT NULL,
    snapshot_schema_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_purchase_request_draft_destination_draft FOREIGN KEY (tenant_id, workspace_id, draft_id)
        REFERENCES sales.purchase_request_draft (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_request_draft_destination_address FOREIGN KEY (tenant_id, workspace_id, address_id)
        REFERENCES sales.client_account_address (tenant_id, workspace_id, id),
    CONSTRAINT ck_purchase_request_draft_destination_schema CHECK (snapshot_schema_version ~ '^[0-9]+\\.[0-9]+$'),
    CONSTRAINT ck_purchase_request_draft_destination_size CHECK (octet_length(address_snapshot::text) <= 32000)
);

CREATE TABLE IF NOT EXISTS sales.purchase_request_draft_route (
    draft_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    provider VARCHAR(40) NOT NULL,
    estimated BOOLEAN NOT NULL DEFAULT TRUE,
    route_snapshot JSONB NOT NULL,
    snapshot_schema_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    calculated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_purchase_request_draft_route_draft FOREIGN KEY (tenant_id, workspace_id, draft_id)
        REFERENCES sales.purchase_request_draft (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_purchase_request_draft_route_schema CHECK (snapshot_schema_version ~ '^[0-9]+\\.[0-9]+$'),
    CONSTRAINT ck_purchase_request_draft_route_size CHECK (octet_length(route_snapshot::text) <= 32000)
);

CREATE TABLE IF NOT EXISTS sales.purchase_request_draft_warehouse_selection (
    draft_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    selection_snapshot JSONB NOT NULL,
    snapshot_schema_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    selected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_purchase_request_draft_selection_draft FOREIGN KEY (tenant_id, workspace_id, draft_id)
        REFERENCES sales.purchase_request_draft (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_request_draft_selection_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT ck_purchase_request_draft_selection_schema CHECK (snapshot_schema_version ~ '^[0-9]+\\.[0-9]+$'),
    CONSTRAINT ck_purchase_request_draft_selection_size CHECK (octet_length(selection_snapshot::text) <= 32000)
);

CREATE TABLE IF NOT EXISTS sales.purchase_request_draft_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    draft_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, buyer_membership_id, idempotency_key),
    CONSTRAINT fk_purchase_request_draft_idempotency_draft FOREIGN KEY (tenant_id, workspace_id, draft_id)
        REFERENCES sales.purchase_request_draft (tenant_id, workspace_id, id)
);

CREATE INDEX IF NOT EXISTS ix_purchase_request_draft_scope_status ON sales.purchase_request_draft
    (tenant_id, workspace_id, buyer_membership_id, status, updated_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_purchase_request_draft_line_sku ON sales.purchase_request_draft_line
    (tenant_id, workspace_id, sku_id, draft_id);
