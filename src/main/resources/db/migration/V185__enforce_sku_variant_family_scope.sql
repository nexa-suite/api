-- Product Variant and Sellable SKU remain owned by the database bootstrap
-- role. The restricted migrator invokes their fixed, one-shot owner helper.
DO $$
DECLARE
    helper_oid oid;
BEGIN
    helper_oid := to_regprocedure('tenant_management.apply_v185_sku_variant_family_scope()');
    IF helper_oid IS NULL OR NOT has_function_privilege(current_user, helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V185 requires its prepared one-shot SKU variant family-scope helper';
    END IF;
END;
$$;

SELECT tenant_management.apply_v185_sku_variant_family_scope();

DO $$
DECLARE
    sku_oid oid;
    variant_oid oid;
    helper_oid oid;
BEGIN
    SELECT relation.oid INTO sku_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'catalog_management'
       AND relation.relname = 'sellable_sku'
       AND relation.relkind = 'r';
    SELECT relation.oid INTO variant_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'catalog_management'
       AND relation.relname = 'product_variant'
       AND relation.relkind = 'r';
    helper_oid := to_regprocedure('tenant_management.apply_v185_sku_variant_family_scope()');

    IF sku_oid IS NULL OR variant_oid IS NULL OR helper_oid IS NULL
        OR (SELECT count(*) FROM pg_constraint
             WHERE ((conrelid = variant_oid AND conname = 'uq_product_variant_scope_family_id'
                        AND contype = 'u')
                    OR (conrelid = sku_oid AND conname = 'fk_sellable_sku_variant_family_scope'
                        AND contype = 'f'))
               AND convalidated) <> 2
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
        RAISE EXCEPTION 'V185 SKU variant scope constraints or one-shot helper ACLs are invalid';
    END IF;
END;
$$;
