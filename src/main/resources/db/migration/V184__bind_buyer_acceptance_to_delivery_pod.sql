-- Keep both logistics tables under their existing owner. The prepared
-- owner-bound one-shot helper applies this fixed Buyer acceptance DDL.
DO $$
DECLARE
    helper_oid oid;
BEGIN
    helper_oid := to_regprocedure('logistics.apply_v184_buyer_acceptance_to_delivery_pod()');
    IF helper_oid IS NULL OR NOT has_function_privilege(current_user, helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V184 requires its prepared one-shot Buyer acceptance and POD helper';
    END IF;
END;
$$;

SELECT logistics.apply_v184_buyer_acceptance_to_delivery_pod();

DO $$
DECLARE
    receipt_oid oid;
    pod_oid oid;
    receipt_owner oid;
    pod_owner oid;
    helper_oid oid;
    buyer_membership_attnum smallint;
    buyer_receipt_attnum smallint;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO receipt_oid, receipt_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'buyer_receipt_fact'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO pod_oid, pod_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'proof_of_delivery'
       AND relation.relkind = 'r';
    helper_oid := to_regprocedure('logistics.apply_v184_buyer_acceptance_to_delivery_pod()');
    SELECT attnum INTO buyer_membership_attnum
      FROM pg_attribute
     WHERE attrelid = pod_oid AND attname = 'buyer_membership_id'
       AND attnum > 0 AND NOT attisdropped;
    SELECT attnum INTO buyer_receipt_attnum
      FROM pg_attribute
     WHERE attrelid = pod_oid AND attname = 'buyer_receipt_fact_id'
       AND attnum > 0 AND NOT attisdropped;

    IF receipt_oid IS NULL OR pod_oid IS NULL OR helper_oid IS NULL
        OR receipt_owner <> pod_owner
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> receipt_owner
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
        RAISE EXCEPTION 'V184 table ownership or one-shot helper grants are invalid';
    END IF;

    IF buyer_membership_attnum IS NULL OR buyer_receipt_attnum IS NULL
        OR (SELECT atttypid FROM pg_attribute WHERE attrelid = pod_oid AND attnum = buyer_membership_attnum)
            <> 'uuid'::regtype
        OR (SELECT atttypid FROM pg_attribute WHERE attrelid = pod_oid AND attnum = buyer_receipt_attnum)
            <> 'uuid'::regtype
        OR EXISTS (
            SELECT 1 FROM pg_attribute
             WHERE attrelid = pod_oid
               AND attname IN ('buyer_membership_id', 'buyer_receipt_fact_id')
               AND attnum > 0 AND NOT attisdropped AND attnotnull
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_attribute
             WHERE attrelid = pod_oid
               AND attname = 'receiver_name'
               AND attnum > 0 AND NOT attisdropped AND NOT attnotnull
        )
        OR (SELECT count(*) FROM pg_constraint
             WHERE ((conrelid = receipt_oid AND conname = 'uq_buyer_receipt_scope_actor_v17' AND contype = 'u')
                    OR (conrelid = pod_oid AND conname IN (
                        'fk_pod_buyer_membership_v17', 'fk_pod_buyer_receipt_v17') AND contype = 'f')
                    OR (conrelid = pod_oid AND conname IN (
                        'ck_pod_receiver_identity_v17', 'ck_pod_buyer_receipt_binding_v17') AND contype = 'c')
                    OR (conrelid = pod_oid AND conname = 'uq_pod_buyer_receipt_v17' AND contype = 'u'))
               AND convalidated) <> 6
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = pod_oid
               AND conname = 'fk_pod_buyer_membership_v17'
               AND confrelid = 'tenant_management.workspace_membership'::regclass
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = pod_oid
               AND conname = 'fk_pod_buyer_receipt_v17'
               AND confrelid = receipt_oid
        ) THEN
        RAISE EXCEPTION 'V184 Buyer acceptance columns or constraints are missing or invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_index index_row
          JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
         WHERE index_row.indrelid = pod_oid
           AND index_class.relname = 'ix_pod_buyer_membership_v17'
           AND index_row.indisvalid
           AND pg_get_expr(index_row.indpred, index_row.indrelid) LIKE '%buyer_membership_id IS NOT NULL%'
    ) THEN
        RAISE EXCEPTION 'V184 Buyer membership POD index is missing or invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_trigger trigger_row
          JOIN pg_proc function_row ON function_row.oid = trigger_row.tgfoid
          JOIN pg_namespace function_schema ON function_schema.oid = function_row.pronamespace
         WHERE trigger_row.tgrelid = pod_oid
           AND trigger_row.tgname = 'logistics_pod_lifecycle_v17'
           AND NOT trigger_row.tgisinternal
           AND function_schema.nspname = 'logistics'
           AND function_row.proname = 'prevent_pod_mutation_buyer_acceptance_v17'
    ) OR EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = pod_oid AND tgname = 'logistics_pod_lifecycle_v16' AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V184 POD lifecycle trigger replacement is missing or invalid';
    END IF;

    IF col_description(pod_oid, buyer_membership_attnum) IS DISTINCT FROM
            'Authenticated Buyer membership that explicitly accepted the delivery represented by this POD.'
        OR col_description(pod_oid, buyer_receipt_attnum) IS DISTINCT FROM
            'Immutable Buyer receipt fact finalized by this POD.' THEN
        RAISE EXCEPTION 'V184 Buyer acceptance column comments are missing';
    END IF;
END;
$$;
