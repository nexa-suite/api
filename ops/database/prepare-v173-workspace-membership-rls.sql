-- Run as the owning database administrator before nexa_migrator applies V173.
-- Keep the restricted migrator away from the full membership row set while
-- granting it one fixed, one-shot RLS DDL operation.
BEGIN;

DO $$
DECLARE
    membership_owner oid;
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V173 preparation must run outside runtime, migrator, and worker sessions';
    END IF;

    SELECT c.relowner
      INTO membership_owner
      FROM pg_class c
     WHERE c.oid = to_regclass('tenant_management.workspace_membership')
       AND c.relkind = 'r';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V173 requires tenant_management.workspace_membership to exist as a table';
    END IF;

    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF current_role_oid <> membership_owner AND NOT current_role_super THEN
        RAISE EXCEPTION 'V173 preparation must run as the membership table owner or an authorized superuser';
    END IF;
    IF NOT has_schema_privilege(current_user, 'tenant_management', 'CREATE')
        OR NOT has_schema_privilege(membership_owner, 'tenant_management', 'CREATE') THEN
        RAISE EXCEPTION 'V173 preparation requires schema CREATE for the DBA and membership table owner';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF NOT FOUND OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = migration_role.oid)
        OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = migration_role.oid)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'nexa_migrator must remain a restricted direct-login role without elevated privileges or memberships';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v173_workspace_membership_rls()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V173 membership RLS may only be applied by nexa_migrator';
    END IF;

    PERFORM set_config('lock_timeout', '5s', true);
    EXECUTE 'ALTER TABLE tenant_management.workspace_membership ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE tenant_management.workspace_membership FORCE ROW LEVEL SECURITY';

    EXECUTE 'DROP POLICY IF EXISTS v173_workspace_membership_current_scope ON tenant_management.workspace_membership';
    EXECUTE 'DROP POLICY IF EXISTS v173_workspace_membership_identity_read ON tenant_management.workspace_membership';
    EXECUTE $policy$
        CREATE POLICY v173_workspace_membership_current_scope
            ON tenant_management.workspace_membership
            FOR ALL
            USING (workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
            WITH CHECK (workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    $policy$;
    EXECUTE $policy$
        CREATE POLICY v173_workspace_membership_identity_read
            ON tenant_management.workspace_membership
            FOR SELECT
            USING (
                user_id::text = nullif(current_setting('app.access_context_user_id', true), '')
                OR (
                    id::text = nullif(current_setting('app.workspace_membership_lookup_id', true), '')
                    AND user_id::text = nullif(current_setting('app.workspace_membership_lookup_user_id', true), '')
                )
            )
    $policy$;

    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM nexa_migrator';
END;
$function$;

REVOKE ALL ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM nexa_runtime;
REVOKE ALL ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM nexa_tenant_database_provisioner;

DO $$
DECLARE
    membership_owner name;
    function_owner name;
    flyway_version integer;
    grantee record;
BEGIN
    SELECT pg_get_userbyid(c.relowner)
      INTO membership_owner
      FROM pg_class c
     WHERE c.oid = 'tenant_management.workspace_membership'::regclass;
    SELECT pg_get_userbyid(p.proowner)
      INTO function_owner
      FROM pg_proc p
     WHERE p.oid = 'tenant_management.apply_v173_workspace_membership_rls()'::regprocedure;

    IF function_owner <> membership_owner THEN
        EXECUTE format('ALTER FUNCTION tenant_management.apply_v173_workspace_membership_rls() OWNER TO %I', membership_owner);
    END IF;

    SELECT max(version::integer)
      INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    FOR grantee IN
        SELECT DISTINCT acl.grantee, grantee_role.rolname
          FROM pg_proc proc
          CROSS JOIN LATERAL aclexplode(coalesce(proc.proacl, acldefault('f', proc.proowner))) acl
          LEFT JOIN pg_roles grantee_role ON grantee_role.oid = acl.grantee
         WHERE proc.oid = 'tenant_management.apply_v173_workspace_membership_rls()'::regprocedure
           AND acl.privilege_type = 'EXECUTE'
           AND acl.grantee <> proc.proowner
           AND NOT (acl.grantee = 'nexa_migrator'::regrole AND coalesce(flyway_version, 0) < 173)
    LOOP
        IF grantee.grantee = 0 THEN
            REVOKE ALL ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM PUBLIC;
        ELSE
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM %I', grantee.rolname);
        END IF;
    END LOOP;

    IF coalesce(flyway_version, 0) < 173 THEN
        GRANT EXECUTE ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() TO nexa_migrator;
        REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM nexa_migrator;
    ELSE
        REVOKE EXECUTE ON FUNCTION tenant_management.apply_v173_workspace_membership_rls() FROM nexa_migrator;
    END IF;

    IF has_table_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'SELECT')
        OR has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'membership_type', 'SELECT')
        OR has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'status', 'SELECT')
        OR EXISTS (
            SELECT 1
              FROM pg_proc proc
              CROSS JOIN LATERAL aclexplode(coalesce(proc.proacl, acldefault('f', proc.proowner))) acl
             WHERE proc.oid = 'tenant_management.apply_v173_workspace_membership_rls()'::regprocedure
               AND acl.grantee = 0
               AND acl.privilege_type = 'EXECUTE'
        )
        OR has_function_privilege('nexa_runtime', 'tenant_management.apply_v173_workspace_membership_rls()', 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', 'tenant_management.apply_v173_workspace_membership_rls()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V173 DDL helper widened runtime, worker, or migrator membership access';
    END IF;
END;
$$;

COMMIT;
