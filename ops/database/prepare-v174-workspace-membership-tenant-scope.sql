-- Run as the authorized database owner before nexa_migrator applies V174.
-- This installs a one-shot fixed DDL helper. The actual membership backfill is
-- performed by nexa_migrator under V173 FORCE RLS, not by this definer.
BEGIN;

DO $$
DECLARE
    membership_owner oid;
    workspace_owner oid;
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V174 preparation must run outside runtime, migrator, and worker sessions';
    END IF;

    SELECT relowner INTO membership_owner
      FROM pg_class
     WHERE oid = to_regclass('tenant_management.workspace_membership')
       AND relkind = 'r';
    SELECT relowner INTO workspace_owner
      FROM pg_class
     WHERE oid = to_regclass('tenant_management.workspace')
       AND relkind = 'r';
    IF membership_owner IS NULL OR workspace_owner IS NULL THEN
        RAISE EXCEPTION 'V174 requires the existing membership and Workspace tables';
    END IF;

    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF current_role_oid <> membership_owner AND NOT current_role_super THEN
        RAISE EXCEPTION 'V174 preparation must run as the membership table owner or an authorized superuser';
    END IF;
    IF NOT has_schema_privilege(current_user, 'tenant_management', 'CREATE')
        OR NOT has_schema_privilege(membership_owner, 'tenant_management', 'CREATE') THEN
        RAISE EXCEPTION 'V174 preparation requires schema CREATE for the DBA and membership table owner';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 173 THEN
        RAISE EXCEPTION 'V174 preparation requires central Flyway version 173 to be the current successful migration';
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

    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = 'tenant_management.workspace_membership'::regclass
           AND attname = 'tenant_id' AND attnum > 0 AND NOT attisdropped
    ) THEN
        RAISE EXCEPTION 'V174 preparation found unversioned workspace_membership.tenant_id drift';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
         WHERE oid = 'tenant_management.workspace_membership'::regclass
           AND relrowsecurity AND relforcerowsecurity
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'tenant_management'
           AND tablename = 'workspace_membership'
           AND policyname = 'v173_workspace_membership_current_scope'
           AND cmd = 'ALL'
           AND qual LIKE '%app.current_workspace_id%'
           AND with_check LIKE '%app.current_workspace_id%'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'tenant_management'
           AND tablename = 'workspace_membership'
           AND policyname = 'v173_workspace_membership_identity_read'
           AND cmd = 'SELECT'
           AND qual LIKE '%app.access_context_user_id%'
           AND qual LIKE '%app.workspace_membership_lookup_id%'
           AND qual LIKE '%app.workspace_membership_lookup_user_id%'
    ) THEN
        RAISE EXCEPTION 'V174 requires unchanged V173 forced RLS and identity-discovery policy';
    END IF;

    IF NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'tenant_id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'workspace_id', 'SELECT')
        OR has_table_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'SELECT')
        OR has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'membership_type', 'SELECT')
        OR has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'status', 'SELECT') THEN
        RAISE EXCEPTION 'V174 requires exact V169 Workspace scan and column-limited membership grants';
    END IF;

    -- The definer owns the referencing table and must be able to add the
    -- composite FK. Do not add broad grants or change table ownership here.
    IF membership_owner <> workspace_owner
        AND NOT (SELECT rolsuper FROM pg_roles WHERE oid = membership_owner)
        AND (NOT has_column_privilege(membership_owner, 'tenant_management.workspace', 'tenant_id', 'REFERENCES')
             OR NOT has_column_privilege(membership_owner, 'tenant_management.workspace', 'id', 'REFERENCES')) THEN
        RAISE EXCEPTION 'V174 requires the membership table owner to hold REFERENCES on Workspace (tenant_id,id)'
            USING HINT = 'Grant REFERENCES (tenant_id,id) on tenant_management.workspace to the membership table owner, then rerun this preflight.';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(p_phase text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V174 membership scope DDL may only be applied by nexa_migrator';
    END IF;

    PERFORM set_config('lock_timeout', '5s', true);
    IF p_phase = 'prepare' THEN
        EXECUTE 'ALTER TABLE tenant_management.workspace_membership ADD COLUMN tenant_id uuid';
        EXECUTE 'GRANT UPDATE (tenant_id) ON tenant_management.workspace_membership TO nexa_migrator';
    ELSIF p_phase = 'finish' THEN
        EXECUTE 'ALTER TABLE tenant_management.workspace_membership ALTER COLUMN tenant_id SET NOT NULL';
        EXECUTE $constraint$
            ALTER TABLE tenant_management.workspace_membership
                ADD CONSTRAINT fk_workspace_membership_tenant_workspace
                FOREIGN KEY (tenant_id, workspace_id)
                REFERENCES tenant_management.workspace (tenant_id, id)
                NOT VALID
        $constraint$;
        EXECUTE 'ALTER TABLE tenant_management.workspace_membership VALIDATE CONSTRAINT fk_workspace_membership_tenant_workspace';

        EXECUTE 'DROP POLICY v173_workspace_membership_current_scope ON tenant_management.workspace_membership';
        EXECUTE $policy$
            CREATE POLICY v174_workspace_membership_tenant_workspace_scope
                ON tenant_management.workspace_membership
                FOR ALL
                USING (
                    tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
                    AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
                )
                WITH CHECK (
                    tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
                    AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
                )
        $policy$;

        EXECUTE 'REVOKE UPDATE (tenant_id) ON tenant_management.workspace_membership FROM nexa_migrator';
        EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) FROM nexa_migrator';
    ELSE
        RAISE EXCEPTION 'V174 membership scope helper accepts only prepare or finish';
    END IF;
END;
$function$;

REVOKE ALL ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) FROM nexa_runtime;
REVOKE ALL ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) FROM nexa_tenant_database_provisioner;

DO $$
DECLARE
    membership_owner name;
    function_owner name;
    grantee record;
BEGIN
    SELECT pg_get_userbyid(c.relowner)
      INTO membership_owner
      FROM pg_class c
     WHERE c.oid = 'tenant_management.workspace_membership'::regclass;
    SELECT pg_get_userbyid(p.proowner)
      INTO function_owner
      FROM pg_proc p
     WHERE p.oid = 'tenant_management.apply_v174_workspace_membership_tenant_scope(text)'::regprocedure;

    IF function_owner <> membership_owner THEN
        EXECUTE format('ALTER FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) OWNER TO %I', membership_owner);
    END IF;

    FOR grantee IN
        SELECT DISTINCT acl.grantee, grantee_role.rolname
          FROM pg_proc proc
          CROSS JOIN LATERAL aclexplode(coalesce(proc.proacl, acldefault('f', proc.proowner))) acl
          LEFT JOIN pg_roles grantee_role ON grantee_role.oid = acl.grantee
         WHERE proc.oid = 'tenant_management.apply_v174_workspace_membership_tenant_scope(text)'::regprocedure
           AND acl.privilege_type = 'EXECUTE'
           AND acl.grantee <> proc.proowner
           AND NOT (acl.grantee = 'nexa_migrator'::regrole)
    LOOP
        IF grantee.grantee = 0 THEN
            REVOKE ALL ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) FROM PUBLIC;
        ELSE
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) FROM %I', grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) TO nexa_migrator;
    REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION tenant_management.apply_v174_workspace_membership_tenant_scope(text) FROM nexa_migrator;

    IF has_function_privilege('nexa_runtime', 'tenant_management.apply_v174_workspace_membership_tenant_scope(text)', 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', 'tenant_management.apply_v174_workspace_membership_tenant_scope(text)', 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc proc
              CROSS JOIN LATERAL aclexplode(coalesce(proc.proacl, acldefault('f', proc.proowner))) acl
             WHERE proc.oid = 'tenant_management.apply_v174_workspace_membership_tenant_scope(text)'::regprocedure
               AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE'
        ) THEN
        RAISE EXCEPTION 'V174 helper must be executable only by nexa_migrator and its owner';
    END IF;
END;
$$;

COMMIT;
