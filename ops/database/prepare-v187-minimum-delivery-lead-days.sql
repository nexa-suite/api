-- Install one V187 helper under operational_settings' V169 migrator owner and
-- another under the authorization-definition owner for the reserved grants.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    settings_oid oid;
    permission_oid oid;
    role_definition_oid oid;
    role_permission_oid oid;
    settings_owner oid;
    permission_owner oid;
    role_definition_owner oid;
    role_permission_owner oid;
    owner_super boolean;
    owner_bypass_rls boolean;
    role_count integer;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V187 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 186 THEN
        RAISE EXCEPTION 'V187 preparation requires central Flyway version 186 to be current';
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
        RAISE EXCEPTION 'V187 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.oid, relation.relowner
      INTO settings_oid, settings_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'operational_settings'
       AND relation.relkind = 'r';
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
    SELECT relation.oid, relation.relowner
      INTO role_permission_oid, role_permission_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'role_permission'
       AND relation.relkind = 'r';
    IF settings_oid IS NULL OR permission_oid IS NULL OR role_definition_oid IS NULL OR role_permission_oid IS NULL
        OR settings_owner <> migration_role.oid
        OR permission_owner <> role_definition_owner
        OR permission_owner = runtime_role.oid
        OR role_permission_owner = runtime_role.oid
        OR pg_has_role(runtime_role.oid, permission_owner, 'MEMBER')
        OR pg_has_role(runtime_role.oid, settings_owner, 'MEMBER')
        OR pg_has_role(runtime_role.oid, role_permission_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V187 requires the V169 settings owner and isolated authorization-definition/role-grant owners';
    END IF;

    SELECT rolsuper, rolbypassrls INTO owner_super, owner_bypass_rls
      FROM pg_roles WHERE oid = permission_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> permission_owner
        AND NOT pg_has_role(current_role_oid, permission_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V187 preparation must run as the authorization-definition owner or an authorized database administrator';
    END IF;
    IF NOT owner_super
        AND (NOT has_schema_privilege(permission_owner, 'tenant_management', 'USAGE')
            OR NOT has_schema_privilege(permission_owner, 'tenant_management', 'CREATE')) THEN
        RAISE EXCEPTION 'The authorization-definition owner must be able to own a helper in tenant_management';
    END IF;
    IF NOT (owner_super OR owner_bypass_rls) THEN
        RAISE EXCEPTION 'The authorization-definition owner must be able to bypass forced RLS for reserved system-role grants';
    END IF;
    IF NOT owner_super
        AND (NOT has_table_privilege(permission_owner, role_permission_oid, 'SELECT')
            OR NOT has_table_privilege(permission_owner, role_permission_oid, 'INSERT')) THEN
        RAISE EXCEPTION 'The authorization-definition owner needs SELECT and INSERT on role_permission for the fixed reserved grants';
    END IF;
    IF NOT has_schema_privilege(migration_role.oid, 'tenant_management', 'USAGE')
        OR NOT has_schema_privilege(migration_role.oid, 'tenant_management', 'CREATE') THEN
        RAISE EXCEPTION 'nexa_migrator must be able to own the fixed V187 operational-settings helper';
    END IF;
    IF NOT has_schema_privilege(current_role_oid, 'tenant_management', 'CREATE') THEN
        RAISE EXCEPTION 'V187 preparation role needs CREATE on tenant_management';
    END IF;

    IF to_regprocedure('tenant_management.apply_v187_minimum_delivery_lead_days()') IS NOT NULL
        OR to_regprocedure('tenant_management.apply_v187_minimum_delivery_lead_days_permissions()') IS NOT NULL THEN
        RAISE EXCEPTION 'V187 one-shot helpers must not exist before their preparation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = settings_oid
           AND attname = 'minimum_delivery_lead_days'
           AND attnum > 0 AND NOT attisdropped
    ) OR EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = settings_oid
           AND conname = 'ck_operational_settings_minimum_delivery_lead_days'
    ) THEN
        RAISE EXCEPTION 'V187 delivery lead-time column and constraint must not exist before their Flyway migration';
    END IF;

    SELECT count(*) INTO role_count
      FROM tenant_management.role_definition role_definition
     WHERE role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code IN ('tenant_admin', 'company_owner')
       AND role_definition.role_type = 'SYSTEM_RESERVED'
       AND role_definition.status = 'ACTIVE';
    IF role_count <> 2 THEN
        RAISE EXCEPTION 'V187 requires active reserved system Tenant Admin and Company Owner roles';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tenant_management.permission_definition permission
         WHERE permission.permission_key = 'tenant.workspace.delivery_lead_time.manage'
           AND (permission.permission_group <> 'TENANT_ADMINISTRATION'
                OR permission.display_name <> 'Manage delivery lead time'
                OR permission.description <> 'Change minimum calendar-day delivery lead time without managing other workspace settings'
                OR NOT permission.reserved)
    ) THEN
        RAISE EXCEPTION 'V187 permission key exists with unexpected metadata';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tenant_management.role_permission role_permission
          JOIN tenant_management.role_definition role_definition
            ON role_definition.id = role_permission.role_id
         WHERE role_permission.permission_key = 'tenant.workspace.delivery_lead_time.manage'
           AND NOT (role_definition.tenant_id IS NULL
                    AND role_definition.workspace_id IS NULL
                    AND role_definition.code IN ('tenant_admin', 'company_owner')
                    AND role_definition.role_type = 'SYSTEM_RESERVED'
                    AND role_definition.status = 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'V187 delivery lead-time permission is already assigned outside Tenant Admin and Company Owner';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    settings_oid oid;
BEGIN
    SELECT relation.oid
      INTO settings_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'operational_settings'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR current_user <> 'nexa_migrator'
        OR settings_oid IS NULL
        OR (SELECT relowner FROM pg_class WHERE oid = settings_oid) <> current_user::regrole::oid THEN
        RAISE EXCEPTION 'V187 settings DDL may only run as nexa_migrator, the operational_settings owner';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = settings_oid
           AND attname = 'minimum_delivery_lead_days'
           AND attnum > 0 AND NOT attisdropped
    ) OR EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = settings_oid
           AND conname = 'ck_operational_settings_minimum_delivery_lead_days'
    ) THEN
        RAISE EXCEPTION 'V187 delivery lead-time column and constraint are already present';
    END IF;

    ALTER TABLE tenant_management.operational_settings
        ADD COLUMN minimum_delivery_lead_days INTEGER NOT NULL DEFAULT 3,
        ADD CONSTRAINT ck_operational_settings_minimum_delivery_lead_days
            CHECK (minimum_delivery_lead_days >= 0);
END;
$function$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days_permissions()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    permission_oid oid;
    role_definition_oid oid;
    role_permission_oid oid;
    permission_owner oid;
    role_definition_owner oid;
    role_count integer;
    grant_count integer;
BEGIN
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
    SELECT relation.oid
      INTO role_permission_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'role_permission'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR permission_oid IS NULL OR role_definition_oid IS NULL OR role_permission_oid IS NULL
        OR permission_owner <> role_definition_owner
        OR current_user <> pg_get_userbyid(permission_owner) THEN
        RAISE EXCEPTION 'V187 reserved grants may only run as nexa_migrator under the authorization-definition owner';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE oid = permission_owner AND (rolsuper OR rolbypassrls))
        OR NOT has_table_privilege(current_user, role_permission_oid, 'SELECT')
        OR NOT has_table_privilege(current_user, role_permission_oid, 'INSERT') THEN
        RAISE EXCEPTION 'V187 authorization-definition owner cannot apply the fixed reserved grants';
    END IF;

    SELECT count(*) INTO role_count
      FROM tenant_management.role_definition role_definition
     WHERE role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code IN ('tenant_admin', 'company_owner')
       AND role_definition.role_type = 'SYSTEM_RESERVED'
       AND role_definition.status = 'ACTIVE';
    IF role_count <> 2 THEN
        RAISE EXCEPTION 'V187 requires active reserved system Tenant Admin and Company Owner roles';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tenant_management.permission_definition permission
         WHERE permission.permission_key = 'tenant.workspace.delivery_lead_time.manage'
           AND (permission.permission_group <> 'TENANT_ADMINISTRATION'
                OR permission.display_name <> 'Manage delivery lead time'
                OR permission.description <> 'Change minimum calendar-day delivery lead time without managing other workspace settings'
                OR NOT permission.reserved)
    ) THEN
        RAISE EXCEPTION 'V187 permission key exists with unexpected metadata';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tenant_management.role_permission role_permission
          JOIN tenant_management.role_definition role_definition
            ON role_definition.id = role_permission.role_id
         WHERE role_permission.permission_key = 'tenant.workspace.delivery_lead_time.manage'
           AND NOT (role_definition.tenant_id IS NULL
                    AND role_definition.workspace_id IS NULL
                    AND role_definition.code IN ('tenant_admin', 'company_owner')
                    AND role_definition.role_type = 'SYSTEM_RESERVED'
                    AND role_definition.status = 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'V187 delivery lead-time permission is already assigned outside Tenant Admin and Company Owner';
    END IF;

    INSERT INTO tenant_management.permission_definition
        (permission_key, permission_group, display_name, description, reserved)
    VALUES
        ('tenant.workspace.delivery_lead_time.manage', 'TENANT_ADMINISTRATION',
         'Manage delivery lead time',
         'Change minimum calendar-day delivery lead time without managing other workspace settings', TRUE)
    ON CONFLICT (permission_key) DO NOTHING;

    INSERT INTO tenant_management.role_permission (role_id, permission_key)
    SELECT role_definition.id, 'tenant.workspace.delivery_lead_time.manage'
      FROM tenant_management.role_definition role_definition
     WHERE role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code IN ('tenant_admin', 'company_owner')
       AND role_definition.role_type = 'SYSTEM_RESERVED'
       AND role_definition.status = 'ACTIVE'
    ON CONFLICT (role_id, permission_key) DO NOTHING;

    IF NOT EXISTS (
        SELECT 1 FROM tenant_management.permission_definition permission
         WHERE permission.permission_key = 'tenant.workspace.delivery_lead_time.manage'
           AND permission.permission_group = 'TENANT_ADMINISTRATION'
           AND permission.display_name = 'Manage delivery lead time'
           AND permission.description = 'Change minimum calendar-day delivery lead time without managing other workspace settings'
           AND permission.reserved
    ) THEN
        RAISE EXCEPTION 'V187 did not persist the reserved delivery lead-time permission';
    END IF;
    SELECT count(*) INTO grant_count
      FROM tenant_management.role_permission role_permission
      JOIN tenant_management.role_definition role_definition
        ON role_definition.id = role_permission.role_id
     WHERE role_permission.permission_key = 'tenant.workspace.delivery_lead_time.manage'
       AND role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code IN ('tenant_admin', 'company_owner')
       AND role_definition.role_type = 'SYSTEM_RESERVED'
       AND role_definition.status = 'ACTIVE';
    IF grant_count <> 2 THEN
        RAISE EXCEPTION 'V187 did not persist exactly the Tenant Admin and Company Owner grants';
    END IF;

    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days_permissions() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    permission_owner oid;
    function_grantee record;
BEGIN
    SELECT relowner INTO permission_owner
      FROM pg_class
     WHERE oid = 'tenant_management.permission_definition'::regclass;

    EXECUTE format('ALTER FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days() OWNER TO nexa_migrator');
    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days() FROM PUBLIC';

    EXECUTE format('ALTER FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days_permissions() OWNER TO %I',
        pg_get_userbyid(permission_owner));

    FOR function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = 'tenant_management.apply_v187_minimum_delivery_lead_days_permissions()'::regprocedure
           AND acl.grantee <> function_catalog.proowner
    LOOP
        IF function_grantee.grantee = 0 THEN
            EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days_permissions() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days_permissions() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION tenant_management.apply_v187_minimum_delivery_lead_days_permissions() TO nexa_migrator;
END;
$$;

COMMIT;
