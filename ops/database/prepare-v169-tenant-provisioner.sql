-- Run as an authorized database administrator before nexa_migrator applies V169.
-- This normalizes any direct worker grants left by an earlier local setup and
-- grants V169's narrow scope-helper reads, V170's exact table ownership needs,
-- and V171's governance scope migration ownership and REFERENCES needs.
BEGIN;

DO $$
DECLARE
    app_schema name;
    database_name name;
    default_acl_detail text;
    migration_role pg_roles%ROWTYPE;
    provisioner_role pg_roles%ROWTYPE;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V169 preparation must run outside runtime, migrator, and worker sessions';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF NOT FOUND OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = migration_role.oid)
        OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = migration_role.oid)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'nexa_migrator must be a direct-login NOINHERIT role without elevated privileges or memberships';
    END IF;

    SELECT * INTO provisioner_role
      FROM pg_roles
     WHERE rolname = 'nexa_tenant_database_provisioner';
    IF FOUND AND (NOT provisioner_role.rolcanlogin OR provisioner_role.rolinherit
        OR provisioner_role.rolsuper OR provisioner_role.rolcreatedb
        OR provisioner_role.rolcreaterole OR provisioner_role.rolreplication
        OR provisioner_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = provisioner_role.oid OR roleid = provisioner_role.oid)) THEN
        RAISE EXCEPTION 'Tenant database provisioner must be a direct NOINHERIT login without elevated privileges or memberships';
    END IF;

    IF to_regnamespace('tenant_management') IS NULL
        OR to_regclass('tenant_management.workspace') IS NULL
        OR to_regclass('tenant_management.workspace_membership') IS NULL
        OR to_regclass('tenant_management.tenant_business_database_binding') IS NULL
        OR to_regclass('tenant_management.tenant_business_database_provisioning_task') IS NULL
        OR to_regclass('tenant_management.tenant_business_database_workspace_anchor_task') IS NULL
        OR to_regclass('tenant_management.organization_registration') IS NULL
        OR to_regclass('tenant_management.membership_admin_event') IS NULL
        OR to_regclass('sales.purchase_request_line') IS NULL
        OR to_regclass('sales.sales_order_line') IS NULL
        OR to_regclass('tenant_management.workspace_settings') IS NULL
        OR to_regclass('tenant_management.operational_settings') IS NULL
        OR to_regclass('tenant_management.notification_preference') IS NULL
        OR to_regclass('tenant_management.organization_invitation_role') IS NULL
        OR to_regclass('tenant_management.role_permission') IS NULL
        OR to_regclass('warehouse.inventory_reservation_line') IS NULL
        OR to_regclass('warehouse.inventory_reservation_allocation') IS NULL
        OR to_regclass('warehouse.reservation_shortage') IS NULL
        OR to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'V169/V170/V171 preparation targets must exist before grants are provisioned';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_tenant_database_provisioner') THEN
        SELECT string_agg(DISTINCT format(
                   'owner=%I object=%s schema=%s',
                   owner_role.rolname,
                   CASE default_acl.defaclobjtype
                       WHEN 'r' THEN 'TABLES'
                       WHEN 'S' THEN 'SEQUENCES'
                       WHEN 'f' THEN 'ROUTINES'
                       WHEN 'T' THEN 'TYPES'
                       WHEN 'n' THEN 'SCHEMAS'
                       ELSE default_acl.defaclobjtype::text
                   END,
                   coalesce(schema_name.nspname, '<all schemas>')
               ), ', ')
          INTO default_acl_detail
          FROM pg_default_acl default_acl
          JOIN pg_roles owner_role ON owner_role.oid = default_acl.defaclrole
          LEFT JOIN pg_namespace schema_name ON schema_name.oid = default_acl.defaclnamespace
          CROSS JOIN LATERAL aclexplode(default_acl.defaclacl) privilege
         WHERE privilege.grantee = 'nexa_tenant_database_provisioner'::regrole;

        IF default_acl_detail IS NOT NULL THEN
            RAISE EXCEPTION 'V169 preparation found direct worker default-privilege grants'
                USING DETAIL = default_acl_detail,
                      HINT = 'As each listed default-ACL owner, revoke the worker grants with ALTER DEFAULT PRIVILEGES, then rerun this preparation.';
        END IF;

        FOR database_name IN
            SELECT datname
              FROM pg_database
             WHERE datallowconn
               AND datname <> current_database()
        LOOP
            EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM nexa_tenant_database_provisioner', database_name);
        END LOOP;

        EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM nexa_tenant_database_provisioner', current_database());
        FOR app_schema IN
            SELECT nspname
              FROM pg_namespace
             WHERE nspname <> 'information_schema'
               AND nspname NOT LIKE 'pg_%'
        LOOP
            EXECUTE format('REVOKE ALL PRIVILEGES ON SCHEMA %I FROM nexa_tenant_database_provisioner', app_schema);
            EXECUTE format('REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I FROM nexa_tenant_database_provisioner', app_schema);
            EXECUTE format('REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I FROM nexa_tenant_database_provisioner', app_schema);
            EXECUTE format('REVOKE ALL PRIVILEGES ON ALL ROUTINES IN SCHEMA %I FROM nexa_tenant_database_provisioner', app_schema);
        END LOOP;

        EXECUTE format('GRANT CONNECT ON DATABASE %I TO nexa_tenant_database_provisioner', current_database());
        GRANT USAGE ON SCHEMA tenant_management TO nexa_tenant_database_provisioner;
    END IF;
END;
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO nexa_migrator', current_database());
END;
$$;

GRANT USAGE, CREATE ON SCHEMA tenant_management TO nexa_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO nexa_migrator;
-- V170 creates policies on existing child tables in these two schemas.
GRANT USAGE ON SCHEMA sales, warehouse TO nexa_migrator;
ALTER TABLE tenant_management.tenant_business_database_binding OWNER TO nexa_migrator;
ALTER TABLE tenant_management.tenant_business_database_provisioning_task OWNER TO nexa_migrator;
ALTER TABLE tenant_management.tenant_business_database_workspace_anchor_task OWNER TO nexa_migrator;
-- V171 adds composite scope constraints and runs a full-row preflight.
ALTER TABLE tenant_management.organization_registration OWNER TO nexa_migrator;
ALTER TABLE tenant_management.membership_admin_event OWNER TO nexa_migrator;
-- V170 adds parent-scoped RLS to these existing child tables. The direct,
-- restricted Flyway login must own each target to alter its RLS metadata.
ALTER TABLE sales.purchase_request_line OWNER TO nexa_migrator;
ALTER TABLE sales.sales_order_line OWNER TO nexa_migrator;
ALTER TABLE tenant_management.workspace_settings OWNER TO nexa_migrator;
ALTER TABLE tenant_management.operational_settings OWNER TO nexa_migrator;
ALTER TABLE tenant_management.notification_preference OWNER TO nexa_migrator;
ALTER TABLE tenant_management.organization_invitation_role OWNER TO nexa_migrator;
ALTER TABLE tenant_management.role_permission OWNER TO nexa_migrator;
ALTER TABLE warehouse.inventory_reservation_line OWNER TO nexa_migrator;
ALTER TABLE warehouse.inventory_reservation_allocation OWNER TO nexa_migrator;
ALTER TABLE warehouse.reservation_shortage OWNER TO nexa_migrator;
ALTER TABLE public.flyway_schema_history OWNER TO nexa_migrator;
REVOKE CREATE ON SCHEMA public FROM nexa_migrator;

REVOKE SELECT ON tenant_management.workspace, tenant_management.workspace_membership
    FROM nexa_migrator;
REVOKE REFERENCES ON tenant_management.workspace, tenant_management.workspace_membership
    FROM nexa_migrator;
DO $$
DECLARE
    target_relation name;
    target_column name;
BEGIN
    FOR target_relation, target_column IN
        SELECT c.relname, a.attname
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE ns.nspname = 'tenant_management'
           AND c.relname IN ('workspace', 'workspace_membership')
           AND a.attnum > 0
           AND NOT a.attisdropped
    LOOP
        EXECUTE format('REVOKE SELECT (%I) ON tenant_management.%I FROM nexa_migrator',
            target_column, target_relation);
        EXECUTE format('REVOKE REFERENCES (%I) ON tenant_management.%I FROM nexa_migrator',
            target_column, target_relation);
    END LOOP;
END;
$$;
GRANT SELECT (tenant_id, id, slug, status)
    ON tenant_management.workspace TO nexa_migrator;
GRANT SELECT (id, workspace_id, user_id)
    ON tenant_management.workspace_membership TO nexa_migrator;
GRANT REFERENCES (tenant_id, id)
    ON tenant_management.workspace TO nexa_migrator;
GRANT REFERENCES (workspace_id, id)
    ON tenant_management.workspace_membership TO nexa_migrator;

DO $$
DECLARE
    target_relation name;
    target_column name;
BEGIN
    IF has_table_privilege('nexa_migrator', 'tenant_management.workspace', 'REFERENCES')
        OR has_table_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'REFERENCES')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'tenant_id', 'REFERENCES')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'id', 'REFERENCES')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'workspace_id', 'REFERENCES')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'id', 'REFERENCES') THEN
        RAISE EXCEPTION 'V171 requires exact composite-key REFERENCES grants for nexa_migrator';
    END IF;

    FOR target_relation, target_column IN
        SELECT c.relname, a.attname
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE ns.nspname = 'tenant_management'
           AND ((c.relname = 'workspace' AND a.attname NOT IN ('tenant_id', 'id'))
             OR (c.relname = 'workspace_membership' AND a.attname NOT IN ('workspace_id', 'id')))
           AND a.attnum > 0
           AND NOT a.attisdropped
    LOOP
        IF has_column_privilege('nexa_migrator',
                format('tenant_management.%I', target_relation), target_column, 'REFERENCES') THEN
            RAISE EXCEPTION 'V171 found an out-of-scope REFERENCES grant for nexa_migrator on tenant_management.%.%',
                target_relation, target_column;
        END IF;
    END LOOP;
END;
$$;

COMMIT;
