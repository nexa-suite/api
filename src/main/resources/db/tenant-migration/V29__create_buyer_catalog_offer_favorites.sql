-- BC-02 Buyer preference: a membership-scoped favorite points to one current
-- BC-03 sellable offer. No Catalog fields are snapshotted in this association.

CREATE UNIQUE INDEX uq_client_account_membership_tenant_workspace_membership
    ON sales.client_account_membership (tenant_id, workspace_id, workspace_membership_id);

CREATE TABLE sales.buyer_catalog_favorite (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    sellable_sku_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT pk_buyer_catalog_favorite
        PRIMARY KEY (tenant_id, workspace_id, buyer_membership_id, sellable_sku_id),
    CONSTRAINT fk_buyer_catalog_favorite_membership
        FOREIGN KEY (tenant_id, workspace_id, buyer_membership_id)
        REFERENCES sales.client_account_membership (tenant_id, workspace_id, workspace_membership_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_buyer_catalog_favorite_sku
        FOREIGN KEY (tenant_id, workspace_id, sellable_sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id)
        ON DELETE CASCADE
);

CREATE INDEX ix_buyer_catalog_favorite_membership_created
    ON sales.buyer_catalog_favorite (tenant_id, workspace_id, buyer_membership_id, created_at DESC,
                                     sellable_sku_id);

COMMENT ON TABLE sales.buyer_catalog_favorite IS
    'BC-02 membership-owned preference for a current BC-03 sellable SKU offer; Catalog data is resolved live.';

ALTER TABLE sales.buyer_catalog_favorite ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.buyer_catalog_favorite FORCE ROW LEVEL SECURITY;

CREATE POLICY buyer_catalog_favorite_verified_membership_scope
    ON sales.buyer_catalog_favorite
    FOR ALL
    USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        AND buyer_membership_id::text = nullif(current_setting('app.current_membership_id', true), '')
    )
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        AND buyer_membership_id::text = nullif(current_setting('app.current_membership_id', true), '')
    );

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        RAISE EXCEPTION 'nexa_runtime role must exist before Tenant migration V29';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA sales TO nexa_runtime;
REVOKE ALL PRIVILEGES ON TABLE sales.buyer_catalog_favorite FROM PUBLIC, nexa_runtime;
GRANT SELECT, INSERT, DELETE ON TABLE sales.buyer_catalog_favorite TO nexa_runtime;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema ON schema.oid = relation.relnamespace
         WHERE schema.nspname = 'sales'
           AND relation.relname = 'buyer_catalog_favorite'
           AND relation.relrowsecurity
           AND relation.relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'Tenant V29 failed to force row security on sales.buyer_catalog_favorite';
    END IF;

    IF has_table_privilege('nexa_runtime', 'sales.buyer_catalog_favorite', 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', 'sales.buyer_catalog_favorite', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V29 unexpectedly granted UPDATE on sales.buyer_catalog_favorite';
    END IF;
END
$$;
