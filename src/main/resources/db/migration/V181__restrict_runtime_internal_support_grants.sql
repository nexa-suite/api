-- Keep Tenant owner operations in runtime while moving operator mutations to
-- the isolated internal-operator database principal.
DO $$
DECLARE
    helper_oid oid;
BEGIN
    SELECT function_row.oid INTO helper_oid
      FROM pg_proc function_row
      JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
     WHERE schema_row.nspname = 'iam'
       AND function_row.proname = 'apply_v181_internal_support_runtime_acl'
       AND function_row.pronargs = 0;
    IF helper_oid IS NULL OR NOT has_function_privilege(current_user, helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V181 requires its prepared one-shot support-request ACL helper';
    END IF;
END;
$$;

SELECT iam.apply_v181_internal_support_runtime_acl();

DO $$
DECLARE
    request_oid oid;
    audit_oid oid;
    helper_oid oid;
BEGIN
    SELECT 'iam.internal_support_request'::regclass INTO request_oid;
    SELECT 'iam.internal_support_audit_event'::regclass INTO audit_oid;
    SELECT function_row.oid INTO helper_oid
      FROM pg_proc function_row
      JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
     WHERE schema_row.nspname = 'iam'
       AND function_row.proname = 'apply_v181_internal_support_runtime_acl'
       AND function_row.pronargs = 0;

    IF helper_oid IS NULL OR has_function_privilege('nexa_migrator', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V181 one-shot internal-support helper remains executable';
    END IF;
    IF has_table_privilege('nexa_runtime', request_oid, 'INSERT')
        OR has_table_privilege('nexa_runtime', request_oid, 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', request_oid, 'INSERT')
        OR has_column_privilege('nexa_runtime', request_oid, 'approved_by_operator_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', request_oid, 'revoked_by_operator_id', 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', audit_oid, 'SELECT')
        OR has_table_privilege('nexa_runtime', audit_oid, 'SELECT') THEN
        RAISE EXCEPTION 'V181 runtime retains internal-operator request or audit privileges';
    END IF;
    IF NOT has_column_privilege('nexa_runtime', request_oid, 'status', 'UPDATE')
        OR NOT has_column_privilege('nexa_runtime', request_oid, 'owner_consented_at', 'UPDATE')
        OR NOT has_column_privilege('nexa_runtime', audit_oid, 'actor_type', 'INSERT')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'iam' AND tablename = 'internal_support_audit_event'
                         AND policyname = 'internal_support_audit_owner_insert_scope' AND cmd = 'INSERT'
                         AND roles = ARRAY['nexa_runtime']::name[]
                         AND with_check LIKE '%COMPANY_OWNER%'
                         AND with_check LIKE '%OWNER_CONSENTED%'
                         AND with_check LIKE '%REVOKED%') THEN
        RAISE EXCEPTION 'V181 removed Company Owner support consent, revocation, or audit access';
    END IF;
END;
$$;
