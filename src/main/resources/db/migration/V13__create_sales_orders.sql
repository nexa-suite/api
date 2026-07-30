CREATE TABLE sales.sales_order_sequence (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    order_year INTEGER NOT NULL,
    next_value BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, order_year),
    CONSTRAINT ck_sales_order_sequence_year CHECK (order_year BETWEEN 2000 AND 9999),
    CONSTRAINT ck_sales_order_sequence_next CHECK (next_value > 0)
);

CREATE TABLE sales.sales_order (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    number VARCHAR(14) NOT NULL,
    client_account_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    source_purchase_request_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    total_amount NUMERIC(18,4) NOT NULL CHECK (total_amount >= 0),
    status VARCHAR(16) NOT NULL,
    rejection_reason VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_sales_order_scope_number UNIQUE (tenant_id, workspace_id, number),
    CONSTRAINT uq_sales_order_source_request UNIQUE (tenant_id, workspace_id, source_purchase_request_id),
    CONSTRAINT fk_sales_order_client FOREIGN KEY (client_account_id) REFERENCES sales.client_account(id),
    CONSTRAINT fk_sales_order_buyer FOREIGN KEY (buyer_membership_id) REFERENCES tenant_management.workspace_membership(id),
    CONSTRAINT fk_sales_order_source_request FOREIGN KEY (source_purchase_request_id) REFERENCES sales.purchase_request(id),
    CONSTRAINT ck_sales_order_status CHECK (status IN ('PENDING','CONFIRMED','REJECTED','CANCELLED')),
    CONSTRAINT ck_sales_order_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_sales_order_rejection_reason CHECK (status <> 'REJECTED' OR (rejection_reason IS NOT NULL AND length(trim(rejection_reason)) > 0))
);

CREATE TABLE sales.sales_order_line (
    id UUID PRIMARY KEY,
    sales_order_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    item_name_snapshot VARCHAR(240) NOT NULL,
    quantity NUMERIC(18,4) NOT NULL CHECK (quantity > 0),
    unit VARCHAR(32) NOT NULL,
    unit_price_amount NUMERIC(18,4) NOT NULL CHECK (unit_price_amount >= 0),
    unit_price_currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_sales_order_line_catalog UNIQUE (sales_order_id, catalog_item_id),
    CONSTRAINT fk_sales_order_line_order FOREIGN KEY (sales_order_id) REFERENCES sales.sales_order(id) ON DELETE CASCADE,
    CONSTRAINT ck_sales_order_line_currency CHECK (unit_price_currency ~ '^[A-Z]{3}$')
);

CREATE TABLE sales.sales_order_event (
    id UUID PRIMARY KEY,
    sales_order_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    reason VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_sales_order_event_order FOREIGN KEY (sales_order_id) REFERENCES sales.sales_order(id) ON DELETE CASCADE
);

CREATE INDEX ix_sales_order_scope_status ON sales.sales_order (tenant_id, workspace_id, status, created_at DESC);
CREATE INDEX ix_sales_order_line_order ON sales.sales_order_line (sales_order_id, created_at, id);

CREATE TRIGGER sales_order_event_append_only
    BEFORE UPDATE OR DELETE ON sales.sales_order_event
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
