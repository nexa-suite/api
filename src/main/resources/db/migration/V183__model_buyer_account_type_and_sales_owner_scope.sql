-- Keep the central Buyer account and membership tables under their existing
-- owner. The one-shot owner-bound helper applies only this fixed migration DDL.
DO $$
DECLARE
    helper_oid oid;
BEGIN
    helper_oid := to_regprocedure('tenant_management.apply_v183_buyer_account_type_sales_owner_scope()');
    IF helper_oid IS NULL OR NOT has_function_privilege(current_user, helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V183 requires its prepared one-shot Buyer account and sales-owner helper';
    END IF;
END;
$$;

SELECT tenant_management.apply_v183_buyer_account_type_sales_owner_scope();

DO $$
DECLARE
    account_oid oid;
    membership_oid oid;
    account_owner oid;
    membership_owner oid;
    helper_oid oid;
    account_type_attnum smallint;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO account_oid, account_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO membership_oid, membership_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'workspace_membership'
       AND relation.relkind = 'r';
    helper_oid := to_regprocedure('tenant_management.apply_v183_buyer_account_type_sales_owner_scope()');
    SELECT attnum INTO account_type_attnum
      FROM pg_attribute
     WHERE attrelid = account_oid AND attname = 'account_type'
       AND attnum > 0 AND NOT attisdropped;

    IF account_oid IS NULL OR membership_oid IS NULL OR helper_oid IS NULL
        OR account_owner <> membership_owner
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> account_owner
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
        RAISE EXCEPTION 'V183 table ownership or one-shot helper grants are invalid';
    END IF;

    IF account_type_attnum IS NULL
        OR NOT (SELECT attnotnull FROM pg_attribute WHERE attrelid = account_oid AND attnum = account_type_attnum)
        OR (SELECT atttypmod FROM pg_attribute WHERE attrelid = account_oid AND attnum = account_type_attnum) <> 20
        OR (SELECT count(*) FROM pg_constraint
             WHERE ((conrelid = account_oid AND conname IN (
                         'ck_client_account_type', 'ck_client_account_tax_by_type',
                         'ck_client_account_peru_ruc_format'))
                    OR (conrelid = membership_oid AND conname = 'uq_workspace_membership_tenant_workspace_id'))
               AND contype IN ('c', 'u') AND convalidated) <> 4
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = account_oid
               AND conname = 'fk_client_account_sales_owner_scope_membership'
               AND contype = 'f' AND convalidated
               AND confrelid = membership_oid
        ) THEN
        RAISE EXCEPTION 'V183 Buyer account constraints are missing or invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_index index_row
          JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
         WHERE index_row.indrelid = account_oid
           AND index_class.relname = 'ix_client_account_sales_owner_scope'
           AND index_row.indisvalid
           AND pg_get_expr(index_row.indpred, index_row.indrelid) LIKE '%sales_owner_membership_id IS NOT NULL%'
    ) THEN
        RAISE EXCEPTION 'V183 sales-owner membership index is missing or invalid';
    END IF;
END;
$$;
