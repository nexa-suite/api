-- Keep both delivery tables under their existing owner. The prepared
-- owner-bound one-shot helper applies this fixed Buyer handoff DDL.
DO $$
DECLARE
    active_attempt_oid oid;
    handoff_token_oid oid;
    active_attempt_owner oid;
    handoff_token_owner oid;
    helper_oid oid;
BEGIN
    IF session_user <> 'nexa_migrator' OR current_user <> session_user THEN
        RAISE EXCEPTION 'V188 requires a direct nexa_migrator session';
    END IF;

    SELECT relation.oid, relation.relowner
      INTO active_attempt_oid, active_attempt_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_active_attempt'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO handoff_token_oid, handoff_token_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_handoff_token'
       AND relation.relkind = 'r';
    helper_oid := to_regprocedure('logistics.apply_v188_buyer_handoff_to_active_attempt()');

    IF active_attempt_oid IS NULL OR handoff_token_oid IS NULL
        OR active_attempt_owner <> handoff_token_owner
        OR helper_oid IS NULL
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> active_attempt_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = helper_oid)
        OR NOT (SELECT coalesce(proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
                  FROM pg_proc WHERE oid = helper_oid)
        OR NOT has_function_privilege(session_user, helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', helper_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = helper_oid AND acl.grantee = 0
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_class
             WHERE oid = active_attempt_oid AND relrowsecurity AND relforcerowsecurity
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_class
             WHERE oid = handoff_token_oid AND relrowsecurity AND relforcerowsecurity
        ) THEN
        RAISE EXCEPTION 'V188 requires the prepared common-owner helper and existing forced-RLS tables';
    END IF;
END;
$$;

SELECT logistics.apply_v188_buyer_handoff_to_active_attempt();

DO $$
DECLARE
    active_attempt_oid oid;
    handoff_token_oid oid;
    active_attempt_owner oid;
    handoff_token_owner oid;
    helper_oid oid;
    trigger_function_oid oid;
    active_attempt_id_attnum smallint;
    terminal_attempt_attnum smallint;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO active_attempt_oid, active_attempt_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_active_attempt'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO handoff_token_oid, handoff_token_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_handoff_token'
       AND relation.relkind = 'r';
    helper_oid := to_regprocedure('logistics.apply_v188_buyer_handoff_to_active_attempt()');
    trigger_function_oid := to_regprocedure('logistics.prevent_delivery_handoff_token_mutation_v188()');
    SELECT attnum INTO active_attempt_id_attnum
      FROM pg_attribute
     WHERE attrelid = handoff_token_oid
       AND attname = 'delivery_active_attempt_id'
       AND attnum > 0 AND NOT attisdropped;
    SELECT attnum INTO terminal_attempt_attnum
      FROM pg_attribute
     WHERE attrelid = handoff_token_oid
       AND attname = 'delivery_attempt_id'
       AND attnum > 0 AND NOT attisdropped;

    IF active_attempt_oid IS NULL OR handoff_token_oid IS NULL OR helper_oid IS NULL
        OR trigger_function_oid IS NULL
        OR active_attempt_owner <> handoff_token_owner
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> active_attempt_owner
        OR (SELECT has_function_privilege('nexa_migrator', helper_oid, 'EXECUTE'))
        OR has_function_privilege('nexa_runtime', helper_oid, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', helper_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = helper_oid AND acl.grantee = 0
        ) THEN
        RAISE EXCEPTION 'V188 did not consume the common-owner helper execution grant';
    END IF;

    IF active_attempt_id_attnum IS NULL OR terminal_attempt_attnum IS NULL
        OR (SELECT atttypid FROM pg_attribute
             WHERE attrelid = handoff_token_oid AND attnum = active_attempt_id_attnum) <> 'uuid'::regtype
        OR (SELECT attnotnull FROM pg_attribute
             WHERE attrelid = handoff_token_oid AND attnum = active_attempt_id_attnum)
        OR (SELECT attnotnull FROM pg_attribute
             WHERE attrelid = handoff_token_oid AND attnum = terminal_attempt_attnum)
        OR EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = handoff_token_oid AND conname = 'fk_handoff_token_attempt'
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = active_attempt_oid
               AND conname = 'uq_delivery_active_attempt_scope_binding'
               AND contype = 'u' AND convalidated
               AND pg_get_constraintdef(oid) LIKE '%UNIQUE (tenant_id, workspace_id, id, delivery_id)%'
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = handoff_token_oid
               AND conname = 'ck_handoff_token_attempt_binding_v188'
               AND contype = 'c' AND convalidated
               AND pg_get_constraintdef(oid) LIKE '%delivery_attempt_id IS NOT NULL%'
               AND pg_get_constraintdef(oid) LIKE '%delivery_active_attempt_id IS NOT NULL%'
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = handoff_token_oid
               AND conname = 'fk_handoff_token_attempt_v188'
               AND contype = 'f' AND convalidated
               AND confrelid = 'logistics.delivery_attempt'::regclass
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = handoff_token_oid
               AND conname = 'fk_handoff_token_active_attempt_v188'
               AND contype = 'f' AND convalidated
               AND confrelid = active_attempt_oid
        ) THEN
        RAISE EXCEPTION 'V188 Buyer handoff binding columns or constraints are missing or invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_index index_row
          JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
         WHERE index_row.indrelid = handoff_token_oid
           AND index_class.relname = 'uq_handoff_token_active_delivery_attempt'
           AND index_row.indisunique AND index_row.indisvalid
           AND lower(pg_get_expr(index_row.indexprs, index_row.indrelid)) LIKE '%coalesce%'
           AND lower(pg_get_expr(index_row.indexprs, index_row.indrelid)) LIKE '%delivery_attempt_id%'
           AND lower(pg_get_expr(index_row.indexprs, index_row.indrelid)) LIKE '%delivery_active_attempt_id%'
           AND pg_get_expr(index_row.indpred, index_row.indrelid) LIKE '%status%ACTIVE%'
    ) THEN
        RAISE EXCEPTION 'V188 scoped active-token unique index is missing or invalid';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
         WHERE oid = active_attempt_oid AND relrowsecurity AND relforcerowsecurity
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_class
         WHERE oid = handoff_token_oid AND relrowsecurity AND relforcerowsecurity
    ) OR (SELECT count(*) FROM pg_policy
           WHERE polrelid IN (active_attempt_oid, handoff_token_oid)) <> 2
        OR NOT EXISTS (
            SELECT 1 FROM pg_policy
             WHERE polrelid = active_attempt_oid
               AND polname = 'v110_delivery_active_attempt_scope'
               AND polcmd = '*' AND polroles = ARRAY[0::oid]
               AND pg_get_expr(polqual, polrelid) LIKE '%current_setting%app.current_tenant_id%'
               AND pg_get_expr(polqual, polrelid) LIKE '%current_setting%app.current_workspace_id%'
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_policy
             WHERE polrelid = handoff_token_oid
               AND polname = 'v17_handoff_token_scope'
               AND polcmd = '*' AND polroles = ARRAY[0::oid]
               AND pg_get_expr(polqual, polrelid) LIKE '%current_setting%app.current_tenant_id%'
               AND pg_get_expr(polqual, polrelid) LIKE '%current_setting%app.current_workspace_id%'
        ) THEN
        RAISE EXCEPTION 'V188 changed or weakened the existing Tenant/Workspace row-security policies';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = handoff_token_oid
           AND tgname = 'logistics_delivery_handoff_token_lifecycle_v188'
           AND NOT tgisinternal AND tgfoid = trigger_function_oid
    ) OR EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgrelid = handoff_token_oid
           AND tgname = 'logistics_delivery_handoff_token_lifecycle_v17'
           AND NOT tgisinternal
    )
        OR (SELECT proowner FROM pg_proc WHERE oid = trigger_function_oid) <> handoff_token_owner
        OR (SELECT prosecdef FROM pg_proc WHERE oid = trigger_function_oid)
        OR NOT (SELECT coalesce(proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
                  FROM pg_proc WHERE oid = trigger_function_oid)
        OR has_function_privilege('nexa_migrator', trigger_function_oid, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', trigger_function_oid, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', trigger_function_oid, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = trigger_function_oid AND acl.grantee = 0
        ) THEN
        RAISE EXCEPTION 'V188 did not replace the lifecycle trigger with the fixed invoker function';
    END IF;

    IF NOT has_table_privilege('nexa_runtime', active_attempt_oid, 'SELECT')
        OR NOT has_table_privilege('nexa_runtime', active_attempt_oid, 'INSERT')
        OR NOT has_table_privilege('nexa_runtime', active_attempt_oid, 'UPDATE')
        OR NOT has_table_privilege('nexa_runtime', active_attempt_oid, 'DELETE')
        OR NOT has_table_privilege('nexa_runtime', handoff_token_oid, 'SELECT')
        OR NOT has_table_privilege('nexa_runtime', handoff_token_oid, 'INSERT')
        OR NOT has_table_privilege('nexa_runtime', handoff_token_oid, 'UPDATE')
        OR NOT has_table_privilege('nexa_runtime', handoff_token_oid, 'DELETE')
        OR has_table_privilege('nexa_runtime', active_attempt_oid, 'TRUNCATE')
        OR has_table_privilege('nexa_runtime', active_attempt_oid, 'REFERENCES')
        OR has_table_privilege('nexa_runtime', active_attempt_oid, 'TRIGGER')
        OR has_table_privilege('nexa_runtime', handoff_token_oid, 'TRUNCATE')
        OR has_table_privilege('nexa_runtime', handoff_token_oid, 'REFERENCES')
        OR has_table_privilege('nexa_runtime', handoff_token_oid, 'TRIGGER')
        OR has_table_privilege('nexa_migrator', active_attempt_oid, 'SELECT')
        OR has_table_privilege('nexa_migrator', active_attempt_oid, 'INSERT')
        OR has_table_privilege('nexa_migrator', active_attempt_oid, 'UPDATE')
        OR has_table_privilege('nexa_migrator', active_attempt_oid, 'DELETE')
        OR has_table_privilege('nexa_migrator', active_attempt_oid, 'TRUNCATE')
        OR has_table_privilege('nexa_migrator', active_attempt_oid, 'REFERENCES')
        OR has_table_privilege('nexa_migrator', active_attempt_oid, 'TRIGGER')
        OR has_table_privilege('nexa_migrator', handoff_token_oid, 'SELECT')
        OR has_table_privilege('nexa_migrator', handoff_token_oid, 'INSERT')
        OR has_table_privilege('nexa_migrator', handoff_token_oid, 'UPDATE')
        OR has_table_privilege('nexa_migrator', handoff_token_oid, 'DELETE')
        OR has_table_privilege('nexa_migrator', handoff_token_oid, 'TRUNCATE')
        OR has_table_privilege('nexa_migrator', handoff_token_oid, 'REFERENCES')
        OR has_table_privilege('nexa_migrator', handoff_token_oid, 'TRIGGER') THEN
        RAISE EXCEPTION 'V188 changed the existing runtime grants or granted direct privileges to nexa_migrator';
    END IF;
END;
$$;
