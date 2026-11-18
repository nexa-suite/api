-- The IAM owner installs this fixed one-shot helper before the restricted migrator runs.
DO $$
DECLARE
    helper_oid oid := to_regprocedure('iam.apply_v190_individual_legal_acceptance()');
    owner_id oid := (SELECT nspowner FROM pg_namespace WHERE nspname = 'iam');
BEGIN
    IF session_user <> 'nexa_migrator' OR current_user <> session_user OR helper_oid IS NULL
        OR NOT EXISTS (SELECT 1 FROM pg_proc WHERE oid=helper_oid AND proowner=owner_id AND prosecdef
                       AND proconfig @> ARRAY['search_path=pg_catalog'])
        OR NOT has_function_privilege('nexa_migrator', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE')
        OR EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                   WHERE p.oid=helper_oid AND a.privilege_type='EXECUTE'
                     AND a.grantee NOT IN (owner_id, (SELECT oid FROM pg_roles WHERE rolname='nexa_migrator')))
        OR has_schema_privilege('nexa_migrator', 'iam', 'CREATE') THEN
        RAISE EXCEPTION 'V190 requires the fixed IAM owner helper and restricted migrator';
    END IF;
END $$;
SELECT iam.apply_v190_individual_legal_acceptance();
