-- V38 retained the road type but did not preserve the separate addressType API value.
-- Existing rows therefore use the contract's default address type when the column is added.
ALTER TABLE sales.buyer_address
    ADD COLUMN address_type VARCHAR(32) NOT NULL DEFAULT 'STREET';

-- Preserve V38's column-scoped write model; this field is the only additional
-- runtime-writable column introduced by this migration.
GRANT INSERT (address_type) ON TABLE sales.buyer_address TO nexa_runtime;
GRANT UPDATE (address_type) ON TABLE sales.buyer_address TO nexa_runtime;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema ON schema.oid = relation.relnamespace
         WHERE schema.nspname = 'sales'
           AND relation.relname = 'buyer_address'
           AND relation.relrowsecurity
           AND relation.relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'Tenant V39 requires forced row security on sales.buyer_address';
    END IF;

    IF NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'address_type', 'INSERT')
        OR NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'address_type', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V39 failed to grant Buyer address type write privileges';
    END IF;

    IF has_table_privilege('nexa_runtime', 'sales.buyer_address', 'INSERT')
        OR has_table_privilege('nexa_runtime', 'sales.buyer_address', 'UPDATE')
        OR has_table_privilege('nexa_runtime', 'sales.buyer_address', 'DELETE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'tenant_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'workspace_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'buyer_membership_id', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V39 broadened Buyer address write privileges';
    END IF;
END
$$;
