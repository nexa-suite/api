-- workspace_membership is a global identity-discovery surface, but each row
-- also carries an immutable Tenant/Workspace pair for write isolation.
DO $$
DECLARE
    migrator pg_roles%ROWTYPE;
    workspace_row record;
    identity_read_policy text;
    identity_read_policy_after text;
    scope_policy text;
    check_policy text;
BEGIN
    SELECT * INTO migrator FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF NOT FOUND OR current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator'
        OR NOT migrator.rolcanlogin OR migrator.rolinherit OR migrator.rolsuper
        OR migrator.rolcreatedb OR migrator.rolcreaterole OR migrator.rolreplication
        OR migrator.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = migrator.oid)
        OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = migrator.oid)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migrator.oid OR roleid = migrator.oid) THEN
        RAISE EXCEPTION 'V174 must run in the restricted direct nexa_migrator session';
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
    ) THEN
        RAISE EXCEPTION 'V174 requires V173 forced membership RLS and its workspace-only write policy';
    END IF;

    SELECT qual INTO identity_read_policy
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'workspace_membership'
       AND policyname = 'v173_workspace_membership_identity_read'
       AND cmd = 'SELECT';
    IF identity_read_policy IS NULL
        OR identity_read_policy NOT LIKE '%app.access_context_user_id%'
        OR identity_read_policy NOT LIKE '%app.workspace_membership_lookup_id%'
        OR identity_read_policy NOT LIKE '%app.workspace_membership_lookup_user_id%' THEN
        RAISE EXCEPTION 'V174 requires the existing V173 identity-discovery SELECT policy';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = 'tenant_management.workspace_membership'::regclass
           AND attname = 'tenant_id' AND attnum > 0 AND NOT attisdropped
    ) THEN
        RAISE EXCEPTION 'V174 requires workspace_membership.tenant_id to be absent before its additive backfill';
    END IF;

    PERFORM tenant_management.apply_v174_workspace_membership_tenant_scope('prepare');

    -- V102 already grants the restricted migrator a bounded Workspace scan.
    -- V173's membership policy reads only current_workspace_id, avoiding the
    -- Workspace -> Membership -> Workspace recursion path during this update.
    PERFORM set_config('app.cross_scope_workspace_scan', 'true', true);
    FOR workspace_row IN
        SELECT tenant_id, id
          FROM tenant_management.workspace
         ORDER BY tenant_id, id
    LOOP
        PERFORM set_config('app.current_tenant_id', workspace_row.tenant_id::text, true);
        PERFORM set_config('app.current_workspace_id', workspace_row.id::text, true);
        UPDATE tenant_management.workspace_membership
           SET tenant_id = workspace_row.tenant_id
         WHERE workspace_id = workspace_row.id;
    END LOOP;
    PERFORM set_config('app.cross_scope_workspace_scan', '', true);
    PERFORM set_config('app.current_tenant_id', '', true);
    PERFORM set_config('app.current_workspace_id', '', true);

    PERFORM tenant_management.apply_v174_workspace_membership_tenant_scope('finish');

    SELECT qual, with_check INTO scope_policy, check_policy
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'workspace_membership'
       AND policyname = 'v174_workspace_membership_tenant_workspace_scope'
       AND cmd = 'ALL';
    IF scope_policy IS NULL
        OR scope_policy NOT LIKE '%app.current_tenant_id%'
        OR scope_policy NOT LIKE '%app.current_workspace_id%'
        OR check_policy IS NULL
        OR check_policy NOT LIKE '%app.current_tenant_id%'
        OR check_policy NOT LIKE '%app.current_workspace_id%' THEN
        RAISE EXCEPTION 'V174 did not install Tenant+Workspace membership write scope';
    END IF;

    SELECT qual INTO identity_read_policy_after
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'workspace_membership'
       AND policyname = 'v173_workspace_membership_identity_read'
       AND cmd = 'SELECT';
    IF identity_read_policy_after IS DISTINCT FROM identity_read_policy THEN
        RAISE EXCEPTION 'V174 must preserve the V173 identity-discovery SELECT policy unchanged';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = 'tenant_management.workspace_membership'::regclass
           AND attname = 'tenant_id' AND attnum > 0 AND NOT attisdropped
           AND NOT attnotnull
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tenant_management.workspace_membership'::regclass
           AND conname = 'fk_workspace_membership_tenant_workspace'
           AND contype = 'f' AND convalidated
    ) THEN
        RAISE EXCEPTION 'V174 did not enforce the non-null Tenant/Workspace foreign key';
    END IF;

    IF has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'tenant_id', 'UPDATE')
        OR has_function_privilege('nexa_migrator',
            'tenant_management.apply_v174_workspace_membership_tenant_scope(text)', 'EXECUTE')
        OR has_function_privilege('nexa_runtime',
            'tenant_management.apply_v174_workspace_membership_tenant_scope(text)', 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner',
            'tenant_management.apply_v174_workspace_membership_tenant_scope(text)', 'EXECUTE') THEN
        RAISE EXCEPTION 'V174 left temporary migrator update or helper execution privileges enabled';
    END IF;
END;
$$;
