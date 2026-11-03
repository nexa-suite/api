-- Run as an authorized database administrator after V174 and before V175.
-- Provision credentials out of band; this script never accepts or prints a password.
-- It installs a fixed, one-shot helper so restricted nexa_migrator can change
-- Workspace RLS and grant only the metadata columns required by the reader.
BEGIN;

DO $$
DECLARE
    reader_role pg_roles%ROWTYPE;
    login_role pg_roles%ROWTYPE;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    workspace_owner oid;
    tenant_owner oid;
    membership_owner oid;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_workspace_scope_reader', 'nexa_workspace_scope_reader_login') THEN
        RAISE EXCEPTION 'V175 preparation must run outside runtime, migrator, and reader sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 174 THEN
        RAISE EXCEPTION 'V175 preparation requires central Flyway version 174 to be current';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    SELECT * INTO runtime_role FROM pg_roles WHERE rolname = 'nexa_runtime';
    SELECT * INTO reader_role FROM pg_roles WHERE rolname = 'nexa_workspace_scope_reader';
    SELECT * INTO login_role FROM pg_roles WHERE rolname = 'nexa_workspace_scope_reader_login';
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
        RAISE EXCEPTION 'V175 requires the restricted direct nexa_runtime login to preserve its authorization helper calls';
    END IF;

    IF reader_role.oid IS NULL OR reader_role.rolcanlogin OR reader_role.rolinherit
        OR reader_role.rolsuper OR reader_role.rolcreatedb OR reader_role.rolcreaterole
        OR reader_role.rolreplication OR reader_role.rolbypassrls
        OR login_role.oid IS NULL OR NOT login_role.rolcanlogin OR login_role.rolinherit
        OR login_role.rolsuper OR login_role.rolcreatedb OR login_role.rolcreaterole
        OR login_role.rolreplication OR login_role.rolbypassrls
        OR (SELECT count(*) FROM pg_auth_members WHERE roleid = reader_role.oid) <> 1
        OR NOT EXISTS (SELECT 1 FROM pg_auth_members
                       WHERE roleid = reader_role.oid AND member = login_role.oid
                         AND NOT inherit_option AND set_option AND NOT admin_option)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = reader_role.oid
                      OR (member = login_role.oid AND roleid <> reader_role.oid)
                      OR roleid = login_role.oid)
        OR EXISTS (SELECT 1 FROM pg_database
                   WHERE datdba IN (reader_role.oid, login_role.oid))
        OR EXISTS (SELECT 1 FROM pg_namespace
                   WHERE nspowner IN (reader_role.oid, login_role.oid)
                      OR has_schema_privilege(reader_role.oid, oid, 'CREATE')
                      OR has_schema_privilege(login_role.oid, oid, 'CREATE'))
        OR EXISTS (SELECT 1 FROM pg_class
                   WHERE relowner IN (reader_role.oid, login_role.oid))
        OR EXISTS (SELECT 1 FROM pg_proc
                   WHERE proowner IN (reader_role.oid, login_role.oid)) THEN
        RAISE EXCEPTION 'V175 requires one isolated NOLOGIN privilege role and one NOINHERIT login with only SET ROLE membership';
    END IF;

    SELECT relowner INTO workspace_owner
      FROM pg_class
     WHERE oid = to_regclass('tenant_management.workspace') AND relkind = 'r';
    SELECT relowner INTO tenant_owner
      FROM pg_class
     WHERE oid = to_regclass('tenant_management.tenant') AND relkind = 'r';
    SELECT relowner INTO membership_owner
      FROM pg_class
     WHERE oid = to_regclass('tenant_management.workspace_membership') AND relkind = 'r';
    IF workspace_owner IS NULL OR tenant_owner IS NULL OR membership_owner IS NULL THEN
        RAISE EXCEPTION 'V175 requires existing Tenant, Workspace, and Workspace Membership tables';
    END IF;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super
        AND ((current_role_oid <> workspace_owner AND NOT pg_has_role(current_role_oid, workspace_owner, 'MEMBER'))
          OR (current_role_oid <> tenant_owner AND NOT pg_has_role(current_role_oid, tenant_owner, 'MEMBER'))
          OR (current_role_oid <> membership_owner AND NOT pg_has_role(current_role_oid, membership_owner, 'MEMBER'))) THEN
        RAISE EXCEPTION 'V175 preparation must run as a member of all Tenant, Workspace, and Workspace Membership table-owner roles, or as a superuser';
    END IF;

    IF (SELECT count(*) FROM pg_proc function_row
         WHERE function_row.oid IN (
             to_regprocedure('tenant_management.bump_authorization_membership(uuid)'),
             to_regprocedure('tenant_management.bump_authorization_memberships(uuid)'),
             to_regprocedure('tenant_management.bump_membership_role_authorization()'),
             to_regprocedure('tenant_management.bump_role_permission_authorization()')
         )) <> 4 THEN
        RAISE EXCEPTION 'V175 requires all four existing authorization version helpers';
    END IF;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND EXISTS (
        SELECT 1 FROM pg_proc function_row
         WHERE function_row.oid IN (
             to_regprocedure('tenant_management.bump_authorization_membership(uuid)'),
             to_regprocedure('tenant_management.bump_authorization_memberships(uuid)'),
             to_regprocedure('tenant_management.bump_membership_role_authorization()'),
             to_regprocedure('tenant_management.bump_role_permission_authorization()')
         )
           AND function_row.proowner <> current_role_oid
    ) THEN
        RAISE EXCEPTION 'V175 function ACL preparation must run as the owner of all four authorization version helpers or as a superuser';
    END IF;

    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.bump_authorization_membership(uuid) FROM PUBLIC';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.bump_authorization_memberships(uuid) FROM PUBLIC';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.bump_membership_role_authorization() FROM PUBLIC';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.bump_role_permission_authorization() FROM PUBLIC';
    EXECUTE 'GRANT EXECUTE ON FUNCTION tenant_management.bump_authorization_membership(uuid) TO nexa_runtime, nexa_migrator';
    EXECUTE 'GRANT EXECUTE ON FUNCTION tenant_management.bump_authorization_memberships(uuid) TO nexa_runtime, nexa_migrator';
    EXECUTE 'GRANT EXECUTE ON FUNCTION tenant_management.bump_membership_role_authorization() TO nexa_migrator';
    EXECUTE 'GRANT EXECUTE ON FUNCTION tenant_management.bump_role_permission_authorization() TO nexa_migrator';

    IF EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
           AND (schema_row.nspname = 'tenant_management'
                OR has_schema_privilege(reader_role.oid, schema_row.oid, 'USAGE'))
           AND has_function_privilege(reader_role.oid, function_row.oid, 'EXECUTE')
           AND NOT (
               schema_row.nspname = 'public'
               AND NOT function_row.prosecdef
               AND EXISTS (
                   SELECT 1
                     FROM pg_depend extension_dependency
                     JOIN pg_extension extension_row
                       ON extension_row.oid = extension_dependency.refobjid
                    WHERE extension_dependency.classid = 'pg_proc'::regclass
                      AND extension_dependency.objid = function_row.oid
                      AND extension_dependency.refclassid = 'pg_extension'::regclass
                      AND extension_dependency.deptype = 'e'
                      AND extension_row.extname = 'btree_gist'
               )
           )
    ) OR EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
           AND has_schema_privilege(login_role.oid, schema_row.oid, 'USAGE')
           AND has_function_privilege(login_role.oid, function_row.oid, 'EXECUTE')
           AND NOT (
               schema_row.nspname = 'public'
               AND NOT function_row.prosecdef
               AND EXISTS (
                   SELECT 1
                     FROM pg_depend extension_dependency
                     JOIN pg_extension extension_row
                       ON extension_row.oid = extension_dependency.refobjid
                    WHERE extension_dependency.classid = 'pg_proc'::regclass
                      AND extension_dependency.objid = function_row.oid
                      AND extension_dependency.refclassid = 'pg_extension'::regclass
                      AND extension_dependency.deptype = 'e'
                      AND extension_row.extname = 'btree_gist'
               )
           )
    ) THEN
        RAISE EXCEPTION 'V175 reader roles must not execute accessible application functions except btree_gist operator support';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class
                   WHERE oid = 'tenant_management.workspace_membership'::regclass
                     AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'V175 preparation requires forced Workspace Membership RLS';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace_membership'
                         AND policyname = 'v173_workspace_membership_identity_read'
                         AND cmd = 'SELECT'
                         AND qual LIKE '%app.access_context_user_id%'
                         AND qual LIKE '%app.workspace_membership_lookup_id%'
                         AND qual LIKE '%app.workspace_membership_lookup_user_id%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace_membership'
                         AND policyname = 'v174_workspace_membership_tenant_workspace_scope'
                         AND cmd = 'ALL'
                         AND qual LIKE '%app.current_tenant_id%'
                         AND qual LIKE '%app.current_workspace_id%'
                         AND with_check LIKE '%app.current_tenant_id%'
                         AND with_check LIKE '%app.current_workspace_id%') THEN
        RAISE EXCEPTION 'V175 preparation requires the V173 and V174 Workspace Membership RLS policies';
    END IF;

    IF NOT current_role_super
        AND (NOT has_schema_privilege(current_user, 'tenant_management', 'CREATE')
          OR NOT has_schema_privilege(workspace_owner, 'tenant_management', 'CREATE')
          OR NOT has_schema_privilege(tenant_owner, 'tenant_management', 'CREATE')
          OR NOT has_schema_privilege(membership_owner, 'tenant_management', 'CREATE')) THEN
        RAISE EXCEPTION 'V175 preparation requires schema CREATE for DBA and table owners unless run by superuser';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_class
                   WHERE oid = 'tenant_management.workspace'::regclass
                     AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class
                       WHERE oid = 'tenant_management.tenant'::regclass
                         AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace'
                         AND policyname = 'v102_workspace_cross_scope_scan'
                         AND cmd = 'SELECT')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace'
                         AND policyname = 'business_documents_scope_reader_ready_workspace'
                         AND permissive = 'RESTRICTIVE'
                         AND cmd = 'SELECT') THEN
        RAISE EXCEPTION 'V175 preparation requires V102 forced RLS and cross-scope Workspace policy';
    END IF;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO nexa_workspace_scope_reader_login', current_database());
    GRANT USAGE ON SCHEMA tenant_management TO nexa_workspace_scope_reader;
    GRANT USAGE ON SCHEMA tenant_management TO nexa_migrator;

END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v175_workspace_scope_reader_policy()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V175 Workspace scan policy may only be applied by nexa_migrator';
    END IF;

    EXECUTE 'DROP POLICY IF EXISTS v102_workspace_cross_scope_scan ON tenant_management.workspace';
    EXECUTE 'DROP POLICY IF EXISTS business_documents_scope_reader_ready_workspace ON tenant_management.workspace';
    EXECUTE $policy$
        CREATE POLICY v175_workspace_scope_reader_scan
            ON tenant_management.workspace
            FOR SELECT
            USING (
                current_user = 'nexa_workspace_scope_reader'
                AND current_setting('app.cross_scope_workspace_scan', true) = 'true'
            )
    $policy$;
    -- The old restrictive policy queried binding and anchor tables from every
    -- Workspace SELECT policy expression. Keep its ACTIVE gate for the
    -- Business Documents reader; readiness remains enforced by that reader's
    -- explicit joins and the binding/anchor RLS policies. The scope reader
    -- must not need SELECT on those unrelated application tables.
    EXECUTE $policy$
        CREATE POLICY business_documents_scope_reader_ready_workspace
            ON tenant_management.workspace
            AS RESTRICTIVE
            FOR SELECT
            USING (
                current_user <> 'nexa_business_documents_scope_reader'
                OR (current_setting('app.cross_scope_workspace_scan', true) = 'true'
                    AND status = 'ACTIVE')
            )
    $policy$;
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_policy() FROM nexa_migrator';
END;
$function$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v175_workspace_scope_reader_workspace_grants()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V175 Workspace reader grants may only be applied by nexa_migrator';
    END IF;
    EXECUTE 'GRANT SELECT (tenant_id, id, status) ON tenant_management.workspace TO nexa_workspace_scope_reader';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_workspace_grants() FROM nexa_migrator';
END;
$function$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_grants()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V175 Tenant reader grants may only be applied by nexa_migrator';
    END IF;
    EXECUTE 'GRANT SELECT (id, status) ON tenant_management.tenant TO nexa_workspace_scope_reader';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_grants() FROM nexa_migrator';
END;
$function$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_policy()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V175 Tenant scan policy may only be applied by nexa_migrator';
    END IF;
    EXECUTE 'CREATE POLICY v175_tenant_scope_reader_scan ON tenant_management.tenant FOR SELECT USING (current_user = ''nexa_workspace_scope_reader'' AND current_setting(''app.cross_scope_workspace_scan'', true) = ''true'')';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_policy() FROM nexa_migrator';
END;
$function$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v175_workspace_scope_reader_membership_grants()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V175 Workspace Membership reader grants may only be applied by nexa_migrator';
    END IF;
    -- Workspace's existing identity-discovery RLS policies query these
    -- columns. A separate RESTRICTIVE policy below ensures this role can
    -- evaluate those predicates but can never see a membership row.
    EXECUTE 'GRANT SELECT (tenant_id, id, workspace_id, user_id) ON tenant_management.workspace_membership TO nexa_workspace_scope_reader';
    EXECUTE 'DROP POLICY IF EXISTS v175_workspace_scope_reader_block_membership ON tenant_management.workspace_membership';
    EXECUTE $policy$
        CREATE POLICY v175_workspace_scope_reader_block_membership
            ON tenant_management.workspace_membership
            AS RESTRICTIVE
            FOR SELECT
            USING (current_user <> 'nexa_workspace_scope_reader')
    $policy$;
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_membership_grants() FROM nexa_migrator';
END;
$function$;

DO $$
DECLARE
    workspace_owner oid;
    tenant_owner oid;
    membership_owner oid;
BEGIN
    SELECT relowner INTO workspace_owner
      FROM pg_class WHERE oid = 'tenant_management.workspace'::regclass;
    SELECT relowner INTO tenant_owner
      FROM pg_class WHERE oid = 'tenant_management.tenant'::regclass;
    SELECT relowner INTO membership_owner
      FROM pg_class WHERE oid = 'tenant_management.workspace_membership'::regclass;
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v175_workspace_scope_reader_policy() OWNER TO %I',
        pg_get_userbyid(workspace_owner));
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v175_workspace_scope_reader_workspace_grants() OWNER TO %I',
        pg_get_userbyid(workspace_owner));
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_grants() OWNER TO %I',
        pg_get_userbyid(tenant_owner));
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_policy() OWNER TO %I',
        pg_get_userbyid(tenant_owner));
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v175_workspace_scope_reader_membership_grants() OWNER TO %I',
        pg_get_userbyid(membership_owner));
END;
$$;

REVOKE ALL ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_policy() FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_workspace_grants() FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_grants() FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_policy() FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_membership_grants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_policy() TO nexa_migrator;
GRANT EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_workspace_grants() TO nexa_migrator;
GRANT EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_grants() TO nexa_migrator;
GRANT EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_tenant_policy() TO nexa_migrator;
GRANT EXECUTE ON FUNCTION tenant_management.apply_v175_workspace_scope_reader_membership_grants() TO nexa_migrator;

COMMIT;
