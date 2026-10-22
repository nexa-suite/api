-- Install a single-use helper owned by the change_event owner. The restricted
-- nexa_migrator cannot alter the original integration table directly.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    change_event_owner oid;
    change_event_owner_super boolean;
    retention_function_owner oid;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V180 preparation must run outside runtime, migrator, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 179 THEN
        RAISE EXCEPTION 'V180 preparation requires central Flyway version 179 to be current';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    SELECT * INTO runtime_role FROM pg_roles WHERE rolname = 'nexa_runtime';
    IF migration_role.oid IS NULL OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'nexa_migrator must remain a restricted direct login without role memberships';
    END IF;
    IF runtime_role.oid IS NULL OR NOT runtime_role.rolcanlogin OR runtime_role.rolsuper
        OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole OR runtime_role.rolreplication
        OR runtime_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = runtime_role.oid OR roleid = runtime_role.oid) THEN
        RAISE EXCEPTION 'V180 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.relowner INTO change_event_owner
      FROM pg_class relation
      JOIN pg_namespace ns ON ns.oid = relation.relnamespace
     WHERE ns.nspname = 'integration'
       AND relation.relname = 'change_event'
       AND relation.relkind = 'r';
    IF change_event_owner IS NULL THEN
        RAISE EXCEPTION 'V180 requires integration.change_event';
    END IF;
    SELECT rolsuper INTO change_event_owner_super FROM pg_roles WHERE oid = change_event_owner;
    SELECT function_row.proowner INTO retention_function_owner
      FROM pg_proc function_row
      JOIN pg_namespace ns ON ns.oid = function_row.pronamespace
     WHERE ns.nspname = 'integration'
       AND function_row.proname = 'purge_expired_change_events'
       AND function_row.proargtypes = '23'::oidvector
       AND function_row.prosecdef;
    IF retention_function_owner IS NULL OR retention_function_owner <> change_event_owner THEN
        RAISE EXCEPTION 'V180 requires the bounded retention function to run as the change-event owner';
    END IF;
    IF change_event_owner = runtime_role.oid
        OR pg_has_role(runtime_role.oid, change_event_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'nexa_runtime must not own integration.change_event or inherit its owner role';
    END IF;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> change_event_owner
        AND NOT pg_has_role(current_role_oid, change_event_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V180 preparation must run as the change-event owner or an authorized database administrator';
    END IF;
    IF NOT change_event_owner_super
        AND (NOT has_schema_privilege(change_event_owner, 'integration', 'USAGE')
            OR NOT has_schema_privilege(change_event_owner, 'tenant_management', 'USAGE')
            OR NOT has_schema_privilege(change_event_owner, 'tenant_management', 'CREATE')) THEN
        RAISE EXCEPTION 'The change-event owner must access integration and own a helper in tenant_management';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v180_change_event_runtime_acl()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    relation_oid oid;
    relation_owner oid;
    column_name name;
    retention_function_oid oid;
    retention_function_owner oid;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO relation_oid, relation_owner
      FROM pg_class relation
      JOIN pg_namespace ns ON ns.oid = relation.relnamespace
     WHERE ns.nspname = 'integration'
       AND relation.relname = 'change_event'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR relation_oid IS NULL
        OR current_user <> pg_get_userbyid(relation_owner) THEN
        RAISE EXCEPTION 'V180 ACL change may only run as nexa_migrator under the change-event owner';
    END IF;

    SELECT function_row.oid, function_row.proowner
      INTO retention_function_oid, retention_function_owner
      FROM pg_proc function_row
      JOIN pg_namespace ns ON ns.oid = function_row.pronamespace
     WHERE ns.nspname = 'integration'
       AND function_row.proname = 'purge_expired_change_events'
       AND function_row.proargtypes = '23'::oidvector
       AND function_row.prosecdef;
    IF retention_function_oid IS NULL OR retention_function_owner <> relation_owner THEN
        RAISE EXCEPTION 'V180 requires the bounded change-event retention function';
    END IF;

    EXECUTE 'REVOKE ALL PRIVILEGES ON TABLE integration.change_event FROM PUBLIC, nexa_runtime';
    FOR column_name IN
        SELECT attname FROM pg_attribute
         WHERE attrelid = relation_oid AND attnum > 0 AND NOT attisdropped
    LOOP
        EXECUTE format(
            'REVOKE ALL PRIVILEGES (%I) ON TABLE integration.change_event FROM PUBLIC, nexa_runtime',
            column_name);
    END LOOP;

    EXECUTE 'GRANT SELECT, INSERT ON TABLE integration.change_event TO nexa_runtime';

    EXECUTE 'ALTER TABLE integration.change_event ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE integration.change_event FORCE ROW LEVEL SECURITY';
    EXECUTE 'DROP POLICY IF EXISTS change_event_runtime_select_scope ON integration.change_event';
    EXECUTE 'DROP POLICY IF EXISTS change_event_runtime_insert_scope ON integration.change_event';
    EXECUTE 'DROP POLICY IF EXISTS change_event_retention_owner_scope ON integration.change_event';
    EXECUTE 'CREATE POLICY change_event_runtime_select_scope ON integration.change_event '
        || 'FOR SELECT TO nexa_runtime USING ('
        || 'tenant_id::text = nullif(current_setting(''app.current_tenant_id'', true), '''') '
        || 'AND workspace_id::text = nullif(current_setting(''app.current_workspace_id'', true), ''''))';
    EXECUTE 'CREATE POLICY change_event_runtime_insert_scope ON integration.change_event '
        || 'FOR INSERT TO nexa_runtime WITH CHECK ('
        || 'tenant_id::text = nullif(current_setting(''app.current_tenant_id'', true), '''') '
        || 'AND workspace_id::text = nullif(current_setting(''app.current_workspace_id'', true), ''''))';
    EXECUTE format('CREATE POLICY change_event_retention_owner_scope ON integration.change_event '
        || 'FOR ALL TO %I USING (true) WITH CHECK (true)', pg_get_userbyid(relation_owner));

    IF has_table_privilege('nexa_runtime', relation_oid, 'UPDATE')
        OR has_table_privilege('nexa_runtime', relation_oid, 'DELETE')
        OR has_table_privilege('nexa_runtime', relation_oid, 'TRUNCATE')
        OR has_table_privilege('nexa_runtime', relation_oid, 'REFERENCES')
        OR has_table_privilege('nexa_runtime', relation_oid, 'TRIGGER')
        OR has_any_column_privilege('nexa_runtime', relation_oid, 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', relation_oid, 'REFERENCES') THEN
        RAISE EXCEPTION 'V180 could not restrict change-event access to read and append';
    END IF;
    IF NOT has_table_privilege('nexa_runtime', relation_oid, 'SELECT')
        OR NOT has_table_privilege('nexa_runtime', relation_oid, 'INSERT')
        OR NOT has_function_privilege('nexa_runtime', retention_function_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V180 must preserve runtime change-feed reads, appends, and bounded retention';
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
        RAISE EXCEPTION 'V180 change-event RLS must force Tenant/Workspace runtime scope and preserve owner retention';
    END IF;

    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v180_change_event_runtime_acl() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    change_event_owner oid;
    function_grantee record;
    helper_oid oid;
BEGIN
    SELECT relowner INTO change_event_owner
      FROM pg_class
     WHERE oid = 'integration.change_event'::regclass;
    helper_oid := 'tenant_management.apply_v180_change_event_runtime_acl()'::regprocedure;

    EXECUTE format('ALTER FUNCTION tenant_management.apply_v180_change_event_runtime_acl() OWNER TO %I',
        pg_get_userbyid(change_event_owner));

    FOR function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = helper_oid
           AND acl.grantee <> function_catalog.proowner
    LOOP
        IF function_grantee.grantee = 0 THEN
            EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v180_change_event_runtime_acl() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v180_change_event_runtime_acl() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION tenant_management.apply_v180_change_event_runtime_acl() TO nexa_migrator;
END;
$$;

COMMIT;
