-- Keep Tenant change-feed reads/appends within the verified Tenant/Workspace.
-- Scheduled retention remains bounded through its SECURITY DEFINER function.
DO $$
DECLARE
    change_event_owner oid;
    retention_function_owner oid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        RAISE EXCEPTION 'Tenant V30 requires the nexa_runtime role';
    END IF;

    SELECT relation.relowner INTO change_event_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'integration'
       AND relation.relname = 'change_event'
       AND relation.relkind = 'r';
    SELECT function_row.proowner INTO retention_function_owner
      FROM pg_proc function_row
      JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
     WHERE schema_row.nspname = 'integration'
       AND function_row.proname = 'purge_expired_change_events'
       AND function_row.proargtypes = '23'::oidvector
       AND function_row.prosecdef;
    IF change_event_owner IS NULL OR change_event_owner <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
        OR retention_function_owner IS DISTINCT FROM change_event_owner THEN
        RAISE EXCEPTION 'Tenant V30 requires nexa_migrator to own the change feed and bounded retention function';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_warehouse_reservation_expiry_worker') THEN
        RAISE EXCEPTION 'Tenant V30 requires scoped traceability and reservation-expiry worker roles';
    END IF;
END;
$$;

REVOKE ALL PRIVILEGES ON TABLE integration.change_event FROM PUBLIC, nexa_runtime;

DO $$
DECLARE
    column_name name;
BEGIN
    FOR column_name IN
        SELECT attname
          FROM pg_attribute
         WHERE attrelid = 'integration.change_event'::regclass
           AND attnum > 0
           AND NOT attisdropped
    LOOP
        EXECUTE format(
            'REVOKE ALL PRIVILEGES (%I) ON TABLE integration.change_event FROM PUBLIC, nexa_runtime',
            column_name);
    END LOOP;

    GRANT SELECT, INSERT ON TABLE integration.change_event TO nexa_runtime;

    ALTER TABLE integration.change_event ENABLE ROW LEVEL SECURITY;
    ALTER TABLE integration.change_event FORCE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS change_event_runtime_select_scope ON integration.change_event;
    DROP POLICY IF EXISTS change_event_runtime_insert_scope ON integration.change_event;
    DROP POLICY IF EXISTS change_event_traceability_worker_insert_scope ON integration.change_event;
    DROP POLICY IF EXISTS change_event_reservation_expiry_worker_insert_scope ON integration.change_event;
    DROP POLICY IF EXISTS change_event_retention_owner_scope ON integration.change_event;
    CREATE POLICY change_event_runtime_select_scope ON integration.change_event
        FOR SELECT TO nexa_runtime
        USING (
            tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        );
    CREATE POLICY change_event_runtime_insert_scope ON integration.change_event
        FOR INSERT TO nexa_runtime
        WITH CHECK (
            tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        );
    CREATE POLICY change_event_traceability_worker_insert_scope ON integration.change_event
        FOR INSERT TO nexa_business_traceability_worker
        WITH CHECK (
            tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        );
    CREATE POLICY change_event_reservation_expiry_worker_insert_scope ON integration.change_event
        FOR INSERT TO nexa_warehouse_reservation_expiry_worker
        WITH CHECK (
            tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        );
    EXECUTE format('CREATE POLICY change_event_retention_owner_scope ON integration.change_event '
        || 'FOR ALL TO %I USING (true) WITH CHECK (true)', current_user);

    IF has_table_privilege('nexa_runtime', 'integration.change_event', 'UPDATE')
        OR has_table_privilege('nexa_runtime', 'integration.change_event', 'DELETE')
        OR has_table_privilege('nexa_runtime', 'integration.change_event', 'TRUNCATE')
        OR has_table_privilege('nexa_runtime', 'integration.change_event', 'REFERENCES')
        OR has_table_privilege('nexa_runtime', 'integration.change_event', 'TRIGGER')
        OR has_any_column_privilege('nexa_runtime', 'integration.change_event', 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', 'integration.change_event', 'REFERENCES') THEN
        RAISE EXCEPTION 'Tenant V30 left non-read/append change-event privileges on nexa_runtime';
    END IF;

    IF NOT has_table_privilege('nexa_runtime', 'integration.change_event', 'SELECT')
        OR NOT has_table_privilege('nexa_runtime', 'integration.change_event', 'INSERT')
        OR NOT has_function_privilege(
            'nexa_runtime', 'integration.purge_expired_change_events(integer)', 'EXECUTE') THEN
        RAISE EXCEPTION 'Tenant V30 removed required change-feed read, append, or retention access';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class
                   WHERE oid = 'integration.change_event'::regclass AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'integration' AND tablename = 'change_event'
                         AND policyname = 'change_event_runtime_select_scope' AND cmd = 'SELECT'
                         AND roles = ARRAY['nexa_runtime']::name[]
                         AND qual LIKE '%app.current_tenant_id%'
                         AND qual LIKE '%app.current_workspace_id%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'integration' AND tablename = 'change_event'
                         AND policyname = 'change_event_runtime_insert_scope' AND cmd = 'INSERT'
                         AND roles = ARRAY['nexa_runtime']::name[]
                         AND with_check LIKE '%app.current_tenant_id%'
                         AND with_check LIKE '%app.current_workspace_id%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'integration' AND tablename = 'change_event'
                         AND policyname IN ('change_event_traceability_worker_insert_scope',
                                            'change_event_reservation_expiry_worker_insert_scope')
                         AND cmd = 'INSERT'
                         AND with_check LIKE '%app.current_tenant_id%'
                         AND with_check LIKE '%app.current_workspace_id%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'integration' AND tablename = 'change_event'
                         AND policyname = 'change_event_retention_owner_scope' AND cmd = 'ALL'
                         AND current_user = ANY (roles)
                         AND qual = 'true' AND with_check = 'true') THEN
        RAISE EXCEPTION 'Tenant V30 must force direct Tenant/Workspace change-feed scope for runtime and workers';
    END IF;
END;
$$;
