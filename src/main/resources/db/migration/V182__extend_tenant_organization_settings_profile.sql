-- organization_settings remains owned and ACL-managed by its existing owner.
-- A fixed one-shot helper lets the restricted migrator apply this profile DDL.
DO $$
DECLARE
    helper_oid oid;
BEGIN
    helper_oid := to_regprocedure('tenant_management.apply_v182_organization_settings_profile()');
    IF helper_oid IS NULL OR NOT has_function_privilege(current_user, helper_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'V182 requires its prepared one-shot organization-settings profile helper';
    END IF;
END;
$$;

SELECT tenant_management.apply_v182_organization_settings_profile();

DO $$
DECLARE
    settings_oid oid;
    settings_owner oid;
    helper_oid oid;
    required_columns text[] := ARRAY[
        'ruc', 'legal_address_country_code', 'legal_address_line',
        'legal_address_department_code', 'legal_address_province_code',
        'legal_address_district_code', 'legal_address_road_type',
        'legal_address_street_name', 'legal_address_street_number',
        'legal_address_interior', 'legal_address_postal_code', 'legal_address_reference'
    ];
BEGIN
    SELECT relation.oid, relation.relowner
      INTO settings_oid, settings_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'organization_settings'
       AND relation.relkind = 'r';
    helper_oid := to_regprocedure('tenant_management.apply_v182_organization_settings_profile()');

    IF settings_oid IS NULL OR helper_oid IS NULL
        OR (SELECT proowner FROM pg_proc WHERE oid = helper_oid) <> settings_owner
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
        RAISE EXCEPTION 'V182 helper ownership or single-use execution grants are invalid';
    END IF;

    IF EXISTS (
        SELECT 1 FROM unnest(required_columns) required(name)
         WHERE NOT EXISTS (
             SELECT 1 FROM pg_attribute attribute
              WHERE attribute.attrelid = settings_oid
                AND attribute.attname = required.name
                AND attribute.attnum > 0 AND NOT attribute.attisdropped
         )
    ) THEN
        RAISE EXCEPTION 'V182 did not add every Tenant organization profile column';
    END IF;
    IF (SELECT count(*) FROM pg_constraint
         WHERE conrelid = settings_oid
           AND conname IN ('ck_organization_settings_ruc_syntax', 'ck_organization_settings_legal_address')
           AND contype = 'c' AND convalidated) <> 2 THEN
        RAISE EXCEPTION 'V182 Tenant organization profile checks are missing or unvalidated';
    END IF;
    IF col_description(settings_oid, (
        SELECT attnum FROM pg_attribute
         WHERE attrelid = settings_oid AND attname = 'ruc' AND attnum > 0 AND NOT attisdropped
    )) IS DISTINCT FROM 'Optional 11-digit RUC syntax only; no legal or tax registry validation is performed.' THEN
        RAISE EXCEPTION 'V182 RUC syntax-only contract comment is missing';
    END IF;
END;
$$;
