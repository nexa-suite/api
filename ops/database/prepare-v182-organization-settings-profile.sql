-- Install a fixed, one-shot helper owned by organization_settings' current
-- owner so the restricted Flyway login can apply V182 without owning the table.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    settings_owner oid;
    settings_owner_super boolean;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V182 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 181 THEN
        RAISE EXCEPTION 'V182 preparation requires central Flyway version 181 to be current';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    SELECT * INTO runtime_role FROM pg_roles WHERE rolname = 'nexa_runtime';
    IF migration_role.oid IS NULL OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'nexa_migrator must remain a restricted direct login without role memberships';
    END IF;
    IF runtime_role.oid IS NULL OR NOT runtime_role.rolcanlogin OR runtime_role.rolsuper
        OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole OR runtime_role.rolreplication
        OR runtime_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = runtime_role.oid OR roleid = runtime_role.oid) THEN
        RAISE EXCEPTION 'V182 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.relowner INTO settings_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'organization_settings'
       AND relation.relkind = 'r';
    IF settings_owner IS NULL OR settings_owner = runtime_role.oid
        OR pg_has_role(runtime_role.oid, settings_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V182 requires organization_settings to remain outside runtime ownership';
    END IF;

    SELECT rolsuper INTO settings_owner_super FROM pg_roles WHERE oid = settings_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> settings_owner
        AND NOT pg_has_role(current_role_oid, settings_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V182 preparation must run as the organization-settings owner or an authorized database administrator';
    END IF;
    IF NOT settings_owner_super
        AND (NOT has_schema_privilege(settings_owner, 'tenant_management', 'USAGE')
            OR NOT has_schema_privilege(settings_owner, 'tenant_management', 'CREATE')) THEN
        RAISE EXCEPTION 'The organization-settings owner must be able to own a helper in tenant_management';
    END IF;
    IF NOT has_schema_privilege(migration_role.oid, 'tenant_management', 'USAGE') THEN
        RAISE EXCEPTION 'nexa_migrator must have existing USAGE on tenant_management to call the V182 helper';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v182_organization_settings_profile()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    settings_oid oid;
    settings_owner oid;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO settings_oid, settings_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'organization_settings'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR settings_oid IS NULL
        OR current_user <> pg_get_userbyid(settings_owner) THEN
        RAISE EXCEPTION 'V182 profile DDL may only run as nexa_migrator under the organization-settings owner';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = settings_oid
           AND attname = ANY (ARRAY[
               'ruc', 'legal_address_country_code', 'legal_address_line',
               'legal_address_department_code', 'legal_address_province_code',
               'legal_address_district_code', 'legal_address_road_type',
               'legal_address_street_name', 'legal_address_street_number',
               'legal_address_interior', 'legal_address_postal_code', 'legal_address_reference'
           ])
           AND attnum > 0 AND NOT attisdropped
    ) THEN
        RAISE EXCEPTION 'V182 profile columns must not exist before their Flyway migration';
    END IF;

    ALTER TABLE tenant_management.organization_settings
        ADD COLUMN ruc VARCHAR(11),
        ADD COLUMN legal_address_country_code VARCHAR(2),
        ADD COLUMN legal_address_line VARCHAR(240),
        ADD COLUMN legal_address_department_code VARCHAR(2),
        ADD COLUMN legal_address_province_code VARCHAR(4),
        ADD COLUMN legal_address_district_code VARCHAR(6),
        ADD COLUMN legal_address_road_type VARCHAR(32),
        ADD COLUMN legal_address_street_name VARCHAR(180),
        ADD COLUMN legal_address_street_number VARCHAR(32),
        ADD COLUMN legal_address_interior VARCHAR(64),
        ADD COLUMN legal_address_postal_code VARCHAR(32),
        ADD COLUMN legal_address_reference VARCHAR(500),
        ADD CONSTRAINT ck_organization_settings_ruc_syntax
            CHECK (ruc IS NULL OR ruc ~ '^[0-9]{11}$'),
        ADD CONSTRAINT ck_organization_settings_legal_address
            CHECK (
                (
                    legal_address_country_code IS NULL
                    AND legal_address_line IS NULL
                    AND legal_address_department_code IS NULL
                    AND legal_address_province_code IS NULL
                    AND legal_address_district_code IS NULL
                    AND legal_address_road_type IS NULL
                    AND legal_address_street_name IS NULL
                    AND legal_address_street_number IS NULL
                    AND legal_address_interior IS NULL
                    AND legal_address_postal_code IS NULL
                    AND legal_address_reference IS NULL
                )
                OR
                (
                    legal_address_country_code IS NOT NULL
                    AND legal_address_country_code ~ '^[A-Z]{2}$'
                    AND legal_address_line IS NOT NULL
                    AND length(btrim(legal_address_line)) BETWEEN 1 AND 240
                    AND (
                        (
                            legal_address_department_code IS NULL
                            AND legal_address_province_code IS NULL
                            AND legal_address_district_code IS NULL
                        )
                        OR
                        (
                            legal_address_country_code = 'PE'
                            AND legal_address_department_code IS NOT NULL
                            AND legal_address_department_code ~ '^[0-9]{2}$'
                            AND legal_address_province_code IS NOT NULL
                            AND legal_address_province_code ~ '^[0-9]{4}$'
                            AND left(legal_address_province_code, 2) = legal_address_department_code
                            AND legal_address_district_code IS NOT NULL
                            AND legal_address_district_code ~ '^[0-9]{6}$'
                            AND left(legal_address_district_code, 4) = legal_address_province_code
                        )
                    )
                )
            );

    COMMENT ON COLUMN tenant_management.organization_settings.ruc IS
        'Optional 11-digit RUC syntax only; no legal or tax registry validation is performed.';
    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v182_organization_settings_profile() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    settings_owner oid;
    helper_oid oid;
    function_grantee record;
BEGIN
    SELECT relowner INTO settings_owner
      FROM pg_class
     WHERE oid = 'tenant_management.organization_settings'::regclass;
    helper_oid := 'tenant_management.apply_v182_organization_settings_profile()'::regprocedure;

    EXECUTE format('ALTER FUNCTION tenant_management.apply_v182_organization_settings_profile() OWNER TO %I',
        pg_get_userbyid(settings_owner));

    FOR function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = helper_oid
           AND acl.grantee <> function_catalog.proowner
    LOOP
        IF function_grantee.grantee = 0 THEN
            EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v182_organization_settings_profile() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v182_organization_settings_profile() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION tenant_management.apply_v182_organization_settings_profile() TO nexa_migrator;
END;
$$;

COMMIT;
