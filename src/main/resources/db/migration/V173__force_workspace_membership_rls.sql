-- Workspace membership is a BC-01 workspace-scoped root. Its identity-first
-- reads use the already verified user id; writes require the current Workspace.
-- The fixed DDL helper preserves the restricted migrator's column-only access.
SELECT tenant_management.apply_v173_workspace_membership_rls();

DO $$
DECLARE
    membership_row_security boolean;
    membership_force_rls boolean;
    helper_executable_by_migrator boolean;
    helper_public_execute boolean;
BEGIN
    SELECT relrowsecurity, relforcerowsecurity
      INTO membership_row_security, membership_force_rls
      FROM pg_class
     WHERE oid = 'tenant_management.workspace_membership'::regclass;

    SELECT has_function_privilege(
        'nexa_migrator',
        'tenant_management.apply_v173_workspace_membership_rls()',
        'EXECUTE'
    ) INTO helper_executable_by_migrator;
    SELECT EXISTS (
        SELECT 1
          FROM pg_proc proc
          CROSS JOIN LATERAL aclexplode(coalesce(proc.proacl, acldefault('f', proc.proowner))) acl
         WHERE proc.oid = 'tenant_management.apply_v173_workspace_membership_rls()'::regprocedure
           AND acl.grantee = 0
           AND acl.privilege_type = 'EXECUTE'
    ) INTO helper_public_execute;

    IF NOT membership_row_security OR NOT membership_force_rls
        OR helper_executable_by_migrator
        OR helper_public_execute
        OR has_table_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'SELECT')
        OR has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'membership_type', 'SELECT')
        OR has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'status', 'SELECT')
        OR has_function_privilege('nexa_runtime',
            'tenant_management.apply_v173_workspace_membership_rls()', 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner',
            'tenant_management.apply_v173_workspace_membership_rls()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V173 failed to enable forced Workspace Membership RLS without broadening access';
    END IF;
END;
$$;
