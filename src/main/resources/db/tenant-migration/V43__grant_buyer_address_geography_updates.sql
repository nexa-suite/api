-- Buyer address updates include their district hierarchy. V38 omitted these
-- geography columns from the runtime UPDATE grant, causing valid address edits
-- to fail at the database boundary.
GRANT UPDATE (department_code, province_code, district_code)
    ON TABLE sales.buyer_address TO nexa_runtime;

DO $$
BEGIN
    IF NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'department_code', 'UPDATE')
        OR NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'province_code', 'UPDATE')
        OR NOT has_column_privilege('nexa_runtime', 'sales.buyer_address', 'district_code', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V43 failed to grant Buyer address geography update privileges';
    END IF;

    IF has_table_privilege('nexa_runtime', 'sales.buyer_address', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'tenant_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'workspace_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'buyer_membership_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', 'sales.buyer_address', 'created_at', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V43 broadened Buyer address update privileges';
    END IF;
END
$$;
