-- BC-03 V1 price-list and customer-terms persistence.
-- catalog_management.sku_price remains the existing Base Price source.
CREATE TABLE catalog_management.price_list (
    price_list_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_price_list_scope_id UNIQUE (price_list_id, tenant_id, workspace_id),
    CONSTRAINT uq_price_list_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_price_list_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_price_list_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_price_list_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT ck_price_list_version CHECK (version >= 0),
    CONSTRAINT ck_price_list_validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

CREATE TABLE catalog_management.price_list_item (
    item_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    price_list_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    CONSTRAINT uq_price_list_item_list_sku UNIQUE (price_list_id, sku_id),
    CONSTRAINT fk_price_list_item_price_list FOREIGN KEY (price_list_id, tenant_id, workspace_id)
        REFERENCES catalog_management.price_list (price_list_id, tenant_id, workspace_id),
    CONSTRAINT fk_price_list_item_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT ck_price_list_item_amount CHECK (unit_price >= 0),
    CONSTRAINT ck_price_list_item_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_price_list_item_validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

CREATE TABLE catalog_management.customer_terms (
    terms_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    customer_account_id UUID NOT NULL,
    price_list_id UUID,
    credit_days INTEGER NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_customer_terms_scope_id UNIQUE (terms_id, tenant_id, workspace_id),
    CONSTRAINT fk_customer_terms_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_customer_terms_price_list FOREIGN KEY (price_list_id, tenant_id, workspace_id)
        REFERENCES catalog_management.price_list (price_list_id, tenant_id, workspace_id),
    CONSTRAINT ck_customer_terms_credit_days CHECK (credit_days >= 0),
    CONSTRAINT ck_customer_terms_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_customer_terms_version CHECK (version >= 0),
    CONSTRAINT ck_customer_terms_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE INDEX ix_price_list_item_sku ON catalog_management.price_list_item (tenant_id, workspace_id, sku_id);
CREATE INDEX ix_price_list_status_validity ON catalog_management.price_list (tenant_id, workspace_id, status, valid_from, valid_to);
CREATE INDEX ix_customer_terms_account_validity ON catalog_management.customer_terms (tenant_id, workspace_id, customer_account_id, valid_from, valid_to);

ALTER TABLE catalog_management.price_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_management.price_list FORCE ROW LEVEL SECURITY;
CREATE POLICY v107_price_list_scope ON catalog_management.price_list
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE catalog_management.price_list_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_management.price_list_item FORCE ROW LEVEL SECURITY;
CREATE POLICY v107_price_list_item_scope ON catalog_management.price_list_item
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE catalog_management.customer_terms ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_management.customer_terms FORCE ROW LEVEL SECURITY;
CREATE POLICY v107_customer_terms_scope ON catalog_management.customer_terms
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        EXECUTE 'REVOKE ALL PRIVILEGES ON catalog_management.price_list, catalog_management.price_list_item, catalog_management.customer_terms FROM nexa_runtime';
        EXECUTE 'GRANT SELECT ON catalog_management.price_list, catalog_management.price_list_item, catalog_management.customer_terms TO nexa_runtime';
    END IF;
END;
$$;
