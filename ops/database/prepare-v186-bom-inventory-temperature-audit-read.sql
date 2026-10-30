-- Install a fixed, one-shot reserved-permission helper under the authorization
-- definition owner. V169 intentionally gives role_permission to nexa_migrator;
-- this helper uses only its fixed inserts while bypassing forced RLS.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    permission_oid oid;
    role_definition_oid oid;
    role_permission_oid oid;
    permission_owner oid;
    role_definition_owner oid;
    role_permission_owner oid;
    owner_super boolean;
    owner_bypass_rls boolean;
    bom_role_count integer;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V186 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 185 THEN
        RAISE EXCEPTION 'V186 preparation requires central Flyway version 185 to be current';
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
        RAISE EXCEPTION 'V186 requires the restricted direct nexa_runtime login';
    END IF;

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
    IF permission_oid IS NULL OR role_definition_oid IS NULL OR role_permission_oid IS NULL
        OR permission_owner <> role_definition_owner
        OR permission_owner = runtime_role.oid
        OR role_permission_owner = runtime_role.oid
        OR pg_has_role(runtime_role.oid, permission_owner, 'MEMBER')
        OR pg_has_role(runtime_role.oid, role_permission_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V186 requires protected authorization-definition and role-grant owners';
    END IF;

    SELECT rolsuper, rolbypassrls INTO owner_super, owner_bypass_rls
      FROM pg_roles WHERE oid = permission_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> permission_owner
        AND NOT pg_has_role(current_role_oid, permission_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V186 preparation must run as the authorization-table owner or an authorized database administrator';
    END IF;
    IF NOT owner_super
        AND (NOT has_schema_privilege(permission_owner, 'tenant_management', 'USAGE')
            OR NOT has_schema_privilege(permission_owner, 'tenant_management', 'CREATE')) THEN
        RAISE EXCEPTION 'The authorization-table owner must own a helper in tenant_management';
    END IF;
    IF NOT (owner_super OR owner_bypass_rls) THEN
        RAISE EXCEPTION 'The authorization-definition owner must be able to bypass forced RLS for reserved system-role grants';
    END IF;
    IF NOT owner_super
        AND (NOT has_table_privilege(permission_owner, role_permission_oid, 'SELECT')
            OR NOT has_table_privilege(permission_owner, role_permission_oid, 'INSERT')) THEN
        RAISE EXCEPTION 'The authorization-definition owner needs SELECT and INSERT on role_permission for the fixed reserved grant';
    END IF;
    IF NOT has_schema_privilege(migration_role.oid, 'tenant_management', 'USAGE') THEN
        RAISE EXCEPTION 'nexa_migrator must have existing USAGE on tenant_management to call the V186 helper';
    END IF;

    SELECT count(*) INTO bom_role_count
      FROM tenant_management.role_definition role_definition
     WHERE role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code = 'business_operations_manager'
       AND role_definition.role_type = 'SYSTEM_TEMPLATE'
       AND role_definition.status = 'ACTIVE';
    IF bom_role_count <> 1 THEN
        RAISE EXCEPTION 'V186 requires exactly one active reserved system BOM role';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tenant_management.permission_definition permission
         WHERE permission.permission_key = 'inventory.temperature.audit.read'
           AND (permission.permission_group <> 'INVENTORY'
                OR permission.display_name <> 'Audit lot temperatures'
                OR permission.description <> 'Read persisted lot temperature observations within the active Tenant and Workspace'
                OR NOT permission.reserved)
    ) THEN
        RAISE EXCEPTION 'V186 permission key exists with unexpected metadata';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tenant_management.role_permission role_permission
          JOIN tenant_management.role_definition role_definition
            ON role_definition.id = role_permission.role_id
         WHERE role_permission.permission_key = 'inventory.temperature.audit.read'
           AND NOT (role_definition.tenant_id IS NULL
                    AND role_definition.workspace_id IS NULL
                    AND role_definition.code = 'business_operations_manager'
                    AND role_definition.role_type = 'SYSTEM_TEMPLATE'
                    AND role_definition.status = 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'V186 temperature-audit permission is already assigned outside the system BOM role';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v186_bom_inventory_temperature_audit_read()
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
    role_permission_owner oid;
    bom_role_count integer;
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
    SELECT relation.oid, relation.relowner
      INTO role_permission_oid, role_permission_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'role_permission'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR permission_oid IS NULL OR role_definition_oid IS NULL OR role_permission_oid IS NULL
        OR permission_owner <> role_definition_owner
        OR current_user <> pg_get_userbyid(permission_owner) THEN
        RAISE EXCEPTION 'V186 BOM permission grant may only run as nexa_migrator under the authorization-definition owner';
    END IF;

    SELECT count(*) INTO bom_role_count
      FROM tenant_management.role_definition role_definition
     WHERE role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code = 'business_operations_manager'
       AND role_definition.role_type = 'SYSTEM_TEMPLATE'
       AND role_definition.status = 'ACTIVE';
    IF bom_role_count <> 1 THEN
        RAISE EXCEPTION 'V186 requires exactly one active reserved system BOM role';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tenant_management.permission_definition permission
         WHERE permission.permission_key = 'inventory.temperature.audit.read'
           AND (permission.permission_group <> 'INVENTORY'
                OR permission.display_name <> 'Audit lot temperatures'
                OR permission.description <> 'Read persisted lot temperature observations within the active Tenant and Workspace'
                OR NOT permission.reserved)
    ) THEN
        RAISE EXCEPTION 'V186 permission key exists with unexpected metadata';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tenant_management.role_permission role_permission
          JOIN tenant_management.role_definition role_definition
            ON role_definition.id = role_permission.role_id
         WHERE role_permission.permission_key = 'inventory.temperature.audit.read'
           AND NOT (role_definition.tenant_id IS NULL
                    AND role_definition.workspace_id IS NULL
                    AND role_definition.code = 'business_operations_manager'
                    AND role_definition.role_type = 'SYSTEM_TEMPLATE'
                    AND role_definition.status = 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'V186 temperature-audit permission is already assigned outside the system BOM role';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE oid = permission_owner AND (rolsuper OR rolbypassrls))
        OR NOT has_table_privilege(current_user, role_permission_oid, 'SELECT')
        OR NOT has_table_privilege(current_user, role_permission_oid, 'INSERT') THEN
        RAISE EXCEPTION 'V186 authorization-definition owner cannot perform the fixed RLS-protected role grant';
    END IF;

    INSERT INTO tenant_management.permission_definition
        (permission_key, permission_group, display_name, description, reserved)
    VALUES
        ('inventory.temperature.audit.read', 'INVENTORY', 'Audit lot temperatures',
         'Read persisted lot temperature observations within the active Tenant and Workspace', TRUE)
    ON CONFLICT (permission_key) DO NOTHING;

    INSERT INTO tenant_management.role_permission (role_id, permission_key)
    SELECT role_definition.id, 'inventory.temperature.audit.read'
      FROM tenant_management.role_definition role_definition
     WHERE role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code = 'business_operations_manager'
       AND role_definition.role_type = 'SYSTEM_TEMPLATE'
       AND role_definition.status = 'ACTIVE'
    ON CONFLICT (role_id, permission_key) DO NOTHING;

    IF NOT EXISTS (
        SELECT 1 FROM tenant_management.permission_definition permission
         WHERE permission.permission_key = 'inventory.temperature.audit.read'
           AND permission.permission_group = 'INVENTORY'
           AND permission.display_name = 'Audit lot temperatures'
           AND permission.description = 'Read persisted lot temperature observations within the active Tenant and Workspace'
           AND permission.reserved
    ) THEN
        RAISE EXCEPTION 'V186 did not persist the reserved BOM temperature-audit permission';
    END IF;
    SELECT count(*) INTO grant_count
      FROM tenant_management.role_permission role_permission
      JOIN tenant_management.role_definition role_definition
        ON role_definition.id = role_permission.role_id
     WHERE role_permission.permission_key = 'inventory.temperature.audit.read'
       AND role_definition.tenant_id IS NULL
       AND role_definition.workspace_id IS NULL
       AND role_definition.code = 'business_operations_manager'
       AND role_definition.role_type = 'SYSTEM_TEMPLATE'
       AND role_definition.status = 'ACTIVE';
    IF grant_count <> 1 THEN
        RAISE EXCEPTION 'V186 did not persist exactly one system BOM temperature-audit grant';
    END IF;

    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v186_bom_inventory_temperature_audit_read() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    permission_owner oid;
    helper_oid oid;
    function_grantee record;
BEGIN
    SELECT relowner INTO permission_owner
      FROM pg_class
     WHERE oid = 'tenant_management.permission_definition'::regclass;
    helper_oid := 'tenant_management.apply_v186_bom_inventory_temperature_audit_read()'::regprocedure;

    EXECUTE format('ALTER FUNCTION tenant_management.apply_v186_bom_inventory_temperature_audit_read() OWNER TO %I',
        pg_get_userbyid(permission_owner));

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
            EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v186_bom_inventory_temperature_audit_read() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v186_bom_inventory_temperature_audit_read() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION tenant_management.apply_v186_bom_inventory_temperature_audit_read() TO nexa_migrator;
END;
$$;

COMMIT;
