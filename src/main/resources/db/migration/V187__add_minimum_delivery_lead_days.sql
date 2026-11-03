-- The V169 owner split keeps operational settings under nexa_migrator while
-- reserved authorization grants remain under the definition-table owner.
DO $$
DECLARE
    settings_helper oid;
    permission_helper oid;
    settings_owner oid;
    permission_owner oid;
    role_definition_owner oid;
BEGIN
    settings_helper := to_regprocedure('tenant_management.apply_v187_minimum_delivery_lead_days()');
    permission_helper := to_regprocedure('tenant_management.apply_v187_minimum_delivery_lead_days_permissions()');
    SELECT relowner INTO settings_owner
      FROM pg_class WHERE oid = 'tenant_management.operational_settings'::regclass;
    SELECT relowner INTO permission_owner
      FROM pg_class WHERE oid = 'tenant_management.permission_definition'::regclass;
    SELECT relowner INTO role_definition_owner
      FROM pg_class WHERE oid = 'tenant_management.role_definition'::regclass;

    IF settings_helper IS NULL OR permission_helper IS NULL
        OR settings_owner <> (SELECT oid FROM pg_roles WHERE rolname = 'nexa_migrator')
        OR (SELECT proowner FROM pg_proc WHERE oid = settings_helper) <> settings_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = settings_helper)
        OR NOT (SELECT coalesce(proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
                  FROM pg_proc WHERE oid = settings_helper)
        OR permission_owner <> role_definition_owner
        OR (SELECT proowner FROM pg_proc WHERE oid = permission_helper) <> permission_owner
        OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = permission_helper)
        OR NOT (SELECT coalesce(proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
                  FROM pg_proc WHERE oid = permission_helper)
        OR NOT has_function_privilege(current_user, settings_helper, 'EXECUTE')
        OR NOT has_function_privilege(current_user, permission_helper, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', settings_helper, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', settings_helper, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', permission_helper, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', permission_helper, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid IN (settings_helper, permission_helper)
               AND acl.grantee = 0
        ) THEN
        RAISE EXCEPTION 'V187 requires its fixed owner-bound helpers with narrow execution grants';
    END IF;
END;
$$;

SELECT tenant_management.apply_v187_minimum_delivery_lead_days();
SELECT tenant_management.apply_v187_minimum_delivery_lead_days_permissions();

-- This DDL helper is owned by the migrator and can revoke its own execution
-- only by being dropped in the same Flyway transaction after the call.
DROP FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days();

DO $$
DECLARE
    settings_oid oid;
    permission_helper oid;
    minimum_days_attnum smallint;
BEGIN
    SELECT relation.oid INTO settings_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'operational_settings'
       AND relation.relkind = 'r';
    permission_helper := to_regprocedure('tenant_management.apply_v187_minimum_delivery_lead_days_permissions()');
    SELECT attnum INTO minimum_days_attnum
      FROM pg_attribute
     WHERE attrelid = settings_oid
       AND attname = 'minimum_delivery_lead_days'
       AND attnum > 0 AND NOT attisdropped;

    IF settings_oid IS NULL OR permission_helper IS NULL OR minimum_days_attnum IS NULL
        OR (SELECT atttypid FROM pg_attribute
             WHERE attrelid = settings_oid AND attnum = minimum_days_attnum) <> 'integer'::regtype
        OR (SELECT attnotnull FROM pg_attribute
             WHERE attrelid = settings_oid AND attnum = minimum_days_attnum) IS NOT TRUE
        OR (SELECT pg_get_expr(default_row.adbin, default_row.adrelid)
              FROM pg_attrdef default_row
             WHERE default_row.adrelid = settings_oid AND default_row.adnum = minimum_days_attnum) <> '3'
        OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = settings_oid
               AND conname = 'ck_operational_settings_minimum_delivery_lead_days'
               AND contype = 'c' AND convalidated
               AND pg_get_constraintdef(oid) LIKE '%minimum_delivery_lead_days >= 0%'
        )
        OR to_regprocedure('tenant_management.apply_v187_minimum_delivery_lead_days()') IS NOT NULL
        OR has_function_privilege('nexa_migrator', permission_helper, 'EXECUTE')
        OR has_function_privilege('nexa_runtime', permission_helper, 'EXECUTE')
        OR has_function_privilege('nexa_tenant_database_provisioner', permission_helper, 'EXECUTE')
        OR EXISTS (
            SELECT 1
              FROM pg_proc function_row
              CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                     acldefault('f', function_row.proowner))) acl
             WHERE function_row.oid = permission_helper AND acl.grantee = 0
        ) THEN
        RAISE EXCEPTION 'V187 delivery lead-time schema or one-shot helper ACLs are invalid';
    END IF;
END;
$$;
