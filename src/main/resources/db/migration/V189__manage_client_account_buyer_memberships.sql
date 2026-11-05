-- Bring the central legacy/pooled account adapter to the same Buyer lifecycle
-- contract used by Tenant databases. Keep existing rows and their RLS intact.
DO $$
DECLARE
    membership_oid oid;
    account_oid oid;
    membership_owner oid;
    account_owner oid;
    workspace_oid oid;
    workspace_owner oid;
    buyer_owner_name text;
    workspace_scan_policy_qual text;
    expected_workspace_scan_policy_qual text;
    helper_oid oid;
    cleanup_helper_oid oid;
BEGIN
    IF session_user <> 'nexa_migrator' OR current_user <> session_user THEN
        RAISE EXCEPTION 'V189 requires a direct nexa_migrator session';
    END IF;

    SELECT relation.oid, relation.relowner
      INTO membership_oid, membership_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account_membership'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO account_oid, account_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO workspace_oid, workspace_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'workspace'
       AND relation.relkind = 'r';
    helper_oid := to_regprocedure('sales.apply_v189_client_account_buyer_memberships()');
    cleanup_helper_oid := to_regprocedure('tenant_management.cleanup_v189_client_account_workspace_scan()');
    buyer_owner_name := pg_get_userbyid(membership_owner);
    expected_workspace_scan_policy_qual := format(
        '((current_user = %L::name) AND (session_user = %L::name) AND (current_setting(%L::text, true) = %L::text))',
        buyer_owner_name, 'nexa_migrator', 'app.v189_workspace_scan', 'true');
    SELECT pg_get_expr(policy.polqual, policy.polrelid)
      INTO workspace_scan_policy_qual
      FROM pg_policy policy
     WHERE policy.polrelid = workspace_oid
       AND policy.polname = 'v189_client_account_membership_workspace_scan';

    IF membership_oid IS NULL OR account_oid IS NULL OR membership_owner <> account_owner
        OR workspace_oid IS NULL
        OR helper_oid IS NULL
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> membership_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = helper_oid)
        OR (SELECT proconfig FROM pg_proc WHERE oid = helper_oid)
               IS DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]
        OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = helper_oid)
               <> '0080bafe120b3afd5c76de717b07e109'
        OR NOT has_function_privilege(session_user, helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', helper_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = helper_oid
               AND acl.grantee <> function_row.proowner
               AND (acl.grantee <> 'nexa_migrator'::regrole OR acl.is_grantable)
        )
        OR cleanup_helper_oid IS NULL
        OR (SELECT proowner FROM pg_proc WHERE oid = cleanup_helper_oid) <> workspace_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = cleanup_helper_oid)
        OR (SELECT proconfig FROM pg_proc WHERE oid = cleanup_helper_oid)
               IS DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]
        OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = cleanup_helper_oid)
               <> 'd1a2eed725a43b0a6d5d1b1eacd0fef4'
        OR NOT has_function_privilege(membership_owner, cleanup_helper_oid, 'EXECUTE')
        OR has_function_privilege(session_user, cleanup_helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', cleanup_helper_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = cleanup_helper_oid
               AND acl.grantee <> function_row.proowner
               AND (acl.grantee <> membership_owner OR acl.is_grantable)
        )
        OR to_regprocedure('tenant_management.v189_client_account_workspace_scopes()') IS NOT NULL
        OR workspace_scan_policy_qual IS NULL
        OR regexp_replace(lower(workspace_scan_policy_qual), '[[:space:]]+', '', 'g')
               <> regexp_replace(lower(expected_workspace_scan_policy_qual), '[[:space:]]+', '', 'g')
        OR NOT EXISTS (
            SELECT 1 FROM pg_policies
             WHERE schemaname = 'tenant_management'
               AND tablename = 'workspace'
               AND policyname = 'v189_client_account_membership_workspace_scan'
               AND cmd = 'SELECT'
               AND permissive = 'PERMISSIVE'
               AND roles = ARRAY['public']::name[]
               AND with_check IS NULL
        )
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = membership_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = account_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = workspace_oid AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'V189 requires the prepared shared-owner helper and existing forced-RLS tables';
    END IF;
END;
$$;

SELECT sales.apply_v189_client_account_buyer_memberships();

DO $$
DECLARE
    membership_oid oid := 'sales.client_account_membership'::regclass;
    account_oid oid := 'sales.client_account'::regclass;
    helper_oid oid := to_regprocedure('sales.apply_v189_client_account_buyer_memberships()');
    workspace_oid oid := 'tenant_management.workspace'::regclass;
    cleanup_helper_oid oid := to_regprocedure('tenant_management.cleanup_v189_client_account_workspace_scan()');
    workspace_owner oid;
    membership_owner oid;
BEGIN
    SELECT relowner INTO workspace_owner FROM pg_class WHERE oid = workspace_oid;
    SELECT relowner INTO membership_owner FROM pg_class WHERE oid = membership_oid;
    IF helper_oid IS NULL
        OR has_function_privilege('nexa_migrator', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = helper_oid
               AND (acl.grantee <> function_row.proowner OR acl.is_grantable)
        )
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> membership_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = helper_oid)
        OR (SELECT proconfig FROM pg_proc WHERE oid = helper_oid)
               IS DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]
        OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = helper_oid)
               <> '0080bafe120b3afd5c76de717b07e109'
        OR cleanup_helper_oid IS NULL
        OR (SELECT proowner FROM pg_proc WHERE oid = cleanup_helper_oid) <> workspace_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = cleanup_helper_oid)
        OR (SELECT proconfig FROM pg_proc WHERE oid = cleanup_helper_oid)
               IS DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]
        OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = cleanup_helper_oid)
               <> 'd1a2eed725a43b0a6d5d1b1eacd0fef4'
        OR NOT has_function_privilege(membership_owner, cleanup_helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_migrator', cleanup_helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', cleanup_helper_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = cleanup_helper_oid
               AND acl.grantee <> function_row.proowner
               AND (acl.grantee <> membership_owner OR acl.is_grantable)
        )
        OR to_regprocedure('tenant_management.v189_client_account_workspace_scopes()') IS NOT NULL
        OR EXISTS (
            SELECT 1 FROM pg_policies
             WHERE schemaname = 'tenant_management'
               AND tablename = 'workspace'
               AND policyname = 'v189_client_account_membership_workspace_scan'
        )
        OR current_setting('app.v189_workspace_scan', true) = 'true'
        OR EXISTS (SELECT 1 FROM pg_class WHERE oid = membership_oid AND NOT relrowsecurity)
        OR EXISTS (SELECT 1 FROM pg_class WHERE oid = membership_oid AND NOT relforcerowsecurity)
        OR EXISTS (SELECT 1 FROM pg_class WHERE oid = account_oid AND NOT relrowsecurity)
        OR EXISTS (SELECT 1 FROM pg_class WHERE oid = account_oid AND NOT relforcerowsecurity)
        OR EXISTS (SELECT 1 FROM pg_class WHERE oid = workspace_oid AND NOT relrowsecurity)
        OR EXISTS (SELECT 1 FROM pg_class WHERE oid = workspace_oid AND NOT relforcerowsecurity)
        OR has_table_privilege('nexa_runtime', membership_oid, 'DELETE')
        OR EXISTS (
            SELECT 1 FROM pg_attribute
             WHERE attrelid = membership_oid
               AND attname IN ('status', 'updated_at', 'version', 'individual_account_id')
               AND attnum > 0 AND NOT attisdropped
               AND (attnotnull IS DISTINCT FROM (attname <> 'individual_account_id'))
        ) THEN
        RAISE EXCEPTION 'V189 Buyer membership lifecycle columns, helper ACLs, or RLS are invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = membership_oid
           AND conname = 'ck_client_account_membership_status_v189'
           AND contype = 'c' AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = membership_oid
           AND conname = 'ck_client_account_membership_version_v189'
           AND contype = 'c' AND convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = membership_oid
           AND conname = 'fk_client_account_membership_individual_account_v189'
           AND contype = 'f' AND convalidated
    ) OR EXISTS (
        SELECT 1 FROM pg_index
         WHERE indexrelid = to_regclass('sales.uq_client_account_one_buyer')
           AND indisvalid
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_index
         WHERE indexrelid = to_regclass('sales.uq_client_account_individual_buyer_v189')
           AND indisunique AND indisvalid
    ) THEN
        RAISE EXCEPTION 'V189 Buyer membership scope constraints or indexes are invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = membership_oid
           AND tgname = 'client_account_membership_identity_guard_v189'
           AND NOT tgisinternal
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = account_oid
           AND tgname = 'client_account_type_membership_guard_v189'
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V189 did not install Buyer membership identity guards';
    END IF;
END;
$$;
