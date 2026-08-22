-- V75 adds the smallest durable seam for the frozen V1 rule:
-- Purchase Request submission creates a SKU + quantity commitment, and
-- rejection/cancellation or Sales Order conversion closes that commitment.
CREATE TABLE sales.commercial_commitment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    purchase_request_id UUID NOT NULL,
    sales_order_id UUID,
    client_account_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    release_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    converted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_commercial_commitment_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_commercial_commitment_request UNIQUE (tenant_id, workspace_id, purchase_request_id),
    CONSTRAINT uq_commercial_commitment_order UNIQUE (tenant_id, workspace_id, sales_order_id),
    CONSTRAINT fk_commercial_commitment_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_commercial_commitment_request FOREIGN KEY (tenant_id, workspace_id, purchase_request_id)
        REFERENCES sales.purchase_request (tenant_id, workspace_id, id),
    CONSTRAINT fk_commercial_commitment_order FOREIGN KEY (tenant_id, workspace_id, sales_order_id)
        REFERENCES sales.sales_order (tenant_id, workspace_id, id),
    CONSTRAINT fk_commercial_commitment_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT ck_commercial_commitment_status CHECK (status IN ('ACTIVE','RELEASED','CONVERTED','EXPIRED','WITHDRAWN')),
    CONSTRAINT ck_commercial_commitment_terminal_data CHECK (
        (status='ACTIVE' AND released_at IS NULL AND converted_at IS NULL AND sales_order_id IS NULL)
        OR (status IN ('RELEASED','EXPIRED','WITHDRAWN') AND released_at IS NOT NULL AND converted_at IS NULL AND sales_order_id IS NULL)
        OR (status='CONVERTED' AND converted_at IS NOT NULL AND sales_order_id IS NOT NULL)
    )
);

CREATE TABLE sales.commercial_commitment_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    commitment_id UUID NOT NULL,
    purchase_request_line_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    sku_code_snapshot VARCHAR(80) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_commercial_commitment_line UNIQUE (tenant_id, workspace_id, commitment_id, sku_id),
    CONSTRAINT fk_commercial_commitment_line_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_commercial_commitment_line_commitment FOREIGN KEY (tenant_id, workspace_id, commitment_id)
        REFERENCES sales.commercial_commitment (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_commercial_commitment_line_request_line FOREIGN KEY (purchase_request_line_id)
        REFERENCES sales.purchase_request_line (id),
    CONSTRAINT fk_commercial_commitment_line_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT ck_commercial_commitment_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_commercial_commitment_line_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_commercial_commitment_scope_status ON sales.commercial_commitment
    (tenant_id, workspace_id, client_account_id, status, updated_at DESC, id);
CREATE INDEX ix_commercial_commitment_line_sku ON sales.commercial_commitment_line
    (tenant_id, workspace_id, sku_id, commitment_id);

DO $$
DECLARE
    target regclass;
    policy_name TEXT;
    entry TEXT;
BEGIN
    FOREACH entry IN ARRAY ARRAY[
        'sales.commercial_commitment|commercial_commitment_scope',
        'sales.commercial_commitment_line|commercial_commitment_line_scope'
    ] LOOP
        target := split_part(entry, '|', 1)::regclass;
        policy_name := split_part(entry, '|', 2);
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('CREATE POLICY %I ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))', policy_name, target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    END LOOP;
END;
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON sales.commercial_commitment, sales.commercial_commitment_line TO nexa_runtime;
    END IF;
END;
$$;
