-- V14 created integration.change_event under the bootstrap owner. The
-- restricted Flyway role uses a one-shot owner-bound helper prepared by the
-- database operator to narrow direct writes and force Tenant/Workspace scope.
DO $$
DECLARE
    helper_oid oid;
BEGIN
    SELECT function_row.oid INTO helper_oid
      FROM pg_proc function_row
      JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND function_row.proname = 'apply_v180_change_event_runtime_acl'
       AND function_row.pronargs = 0;
    IF helper_oid IS NULL OR NOT has_function_privilege(current_user, helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V180 requires its prepared one-shot change-event ACL helper';
    END IF;
END;
$$;

SELECT tenant_management.apply_v180_change_event_runtime_acl();

DO $$
DECLARE
    relation_oid oid;
    helper_oid oid;
    retention_function_oid oid;
    relation_owner oid;
BEGIN
    SELECT relation.oid INTO relation_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'integration'
       AND relation.relname = 'change_event'
       AND relation.relkind = 'r';
    SELECT relowner INTO relation_owner FROM pg_class WHERE oid = relation_oid;
    SELECT function_row.oid INTO helper_oid
      FROM pg_proc function_row
      JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND function_row.proname = 'apply_v180_change_event_runtime_acl'
       AND function_row.pronargs = 0;
    SELECT function_row.oid INTO retention_function_oid
      FROM pg_proc function_row
      JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
     WHERE schema_row.nspname = 'integration'
       AND function_row.proname = 'purge_expired_change_events'
       AND function_row.proargtypes = '23'::oidvector;

    IF helper_oid IS NULL OR has_function_privilege('nexa_migrator', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V180 one-shot change-event ACL helper remains executable';
    END IF;

    IF relation_oid IS NULL OR relation_owner IS NULL OR retention_function_oid IS NULL
        OR has_table_privilege('nexa_runtime', relation_oid, 'UPDATE')
        OR has_table_privilege('nexa_runtime', relation_oid, 'DELETE')
        OR has_table_privilege('nexa_runtime', relation_oid, 'TRUNCATE')
        OR has_table_privilege('nexa_runtime', relation_oid, 'REFERENCES')
        OR has_table_privilege('nexa_runtime', relation_oid, 'TRIGGER')
        OR has_any_column_privilege('nexa_runtime', relation_oid, 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', relation_oid, 'REFERENCES') THEN
        RAISE EXCEPTION 'V180 left non-read/append change-event privileges on nexa_runtime';
    END IF;

    IF NOT has_table_privilege('nexa_runtime', relation_oid, 'SELECT')
        OR NOT has_table_privilege('nexa_runtime', relation_oid, 'INSERT')
        OR NOT has_function_privilege('nexa_runtime', retention_function_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V180 removed required change-feed read, append, or retention access';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = relation_oid AND relrowsecurity AND relforcerowsecurity)
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
                         AND policyname = 'change_event_retention_owner_scope' AND cmd = 'ALL'
                         AND pg_get_userbyid(relation_owner) = ANY (roles)
                         AND qual = 'true' AND with_check = 'true') THEN
        RAISE EXCEPTION 'V180 must force direct Tenant/Workspace change-feed scope and preserve bounded owner retention';
    END IF;
END;
$$;
