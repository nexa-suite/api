-- The restricted migrator adds the reserved permission and its sole BOM grant
-- through the one-shot owner-bound helper prepared by the database operator.
DO $$
DECLARE
    helper_oid oid;
    permission_oid oid;
    role_definition_oid oid;
    permission_owner oid;
    role_definition_owner oid;
BEGIN
    helper_oid := to_regprocedure('tenant_management.apply_v186_bom_inventory_temperature_audit_read()');
    SELECT relation.oid, relation.relowner
      INTO permission_oid, permission_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'permission_definition'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO role_definition_oid, role_definition_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'role_definition'
       AND relation.relkind = 'r';
    IF helper_oid IS NULL OR permission_oid IS NULL OR role_definition_oid IS NULL
        OR permission_owner <> role_definition_owner
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> permission_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = helper_oid)
        OR NOT (SELECT coalesce(proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
                  FROM pg_proc WHERE oid = helper_oid)
        OR NOT has_function_privilege(current_user, helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V186 requires its prepared one-shot BOM temperature-audit permission helper';
    END IF;
END;
$$;

SELECT tenant_management.apply_v186_bom_inventory_temperature_audit_read();

DO $$
DECLARE
    helper_oid oid;
BEGIN
    helper_oid := to_regprocedure('tenant_management.apply_v186_bom_inventory_temperature_audit_read()');
    IF helper_oid IS NULL
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <>
           (SELECT relowner FROM pg_class WHERE oid = 'tenant_management.permission_definition'::regclass)
        OR has_function_privilege('nexa_migrator', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', helper_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = helper_oid AND acl.grantee = 0
        ) THEN
        RAISE EXCEPTION 'V186 one-shot BOM temperature-audit helper remains executable';
    END IF;
END;
$$;
