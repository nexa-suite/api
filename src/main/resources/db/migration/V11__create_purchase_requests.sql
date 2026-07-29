CREATE TABLE sales.purchase_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    requested_delivery_date DATE,
    delivery_profile_snapshot TEXT,
    payment_option VARCHAR(80),
    comments TEXT,
    review_note TEXT,
    reviewed_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_purchase_request_scope_code UNIQUE (tenant_id, workspace_id, code),
    CONSTRAINT fk_purchase_request_account FOREIGN KEY (client_account_id) REFERENCES sales.client_account(id),
    CONSTRAINT fk_purchase_request_buyer FOREIGN KEY (buyer_membership_id) REFERENCES tenant_management.workspace_membership(id),
    CONSTRAINT ck_purchase_request_status CHECK (status IN ('DRAFT','SUBMITTED','IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','REJECTED','CANCELLED','CONVERTED_TO_ORDER'))
);

CREATE TABLE sales.purchase_request_line (
    id UUID PRIMARY KEY,
    purchase_request_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    item_name_snapshot VARCHAR(240) NOT NULL,
    presentation_snapshot VARCHAR(240) NOT NULL,
    quantity NUMERIC(18,4) NOT NULL CHECK (quantity > 0),
    unit VARCHAR(32) NOT NULL,
    unit_price_amount NUMERIC(18,4) NOT NULL CHECK (unit_price_amount >= 0),
    unit_price_currency VARCHAR(3) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_purchase_request_catalog_item UNIQUE (purchase_request_id, catalog_item_id),
    CONSTRAINT fk_purchase_request_line_request FOREIGN KEY (purchase_request_id) REFERENCES sales.purchase_request(id) ON DELETE CASCADE
);

CREATE TABLE sales.purchase_request_event (
    id UUID PRIMARY KEY,
    purchase_request_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_purchase_request_event_request FOREIGN KEY (purchase_request_id) REFERENCES sales.purchase_request(id) ON DELETE CASCADE
);

CREATE TABLE sales.idempotency_record (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    resource_id UUID NOT NULL,
    response_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_sales_idempotency UNIQUE (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key)
);

CREATE INDEX ix_purchase_request_scope_status ON sales.purchase_request (tenant_id, workspace_id, status, created_at DESC);
