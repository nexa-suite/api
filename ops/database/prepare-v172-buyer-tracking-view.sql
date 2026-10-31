-- Run as an authorized database administrator after V171 and before V172.
-- The restricted Flyway role must own the view to enable invoker security.
BEGIN;

DO $$
DECLARE
    migration_role pg_roles%ROWTYPE;
    tracking_view_kind "char";
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V172 preparation must run outside runtime, migrator, and worker sessions';
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

    SELECT c.relkind
      INTO tracking_view_kind
      FROM pg_class c
     WHERE c.oid = to_regclass('logistics.buyer_delivery_tracking');
    IF NOT FOUND OR tracking_view_kind <> 'v' THEN
        RAISE EXCEPTION 'V172 requires logistics.buyer_delivery_tracking to exist as a view';
    END IF;
END;
$$;

GRANT USAGE, CREATE ON SCHEMA logistics TO nexa_migrator;
ALTER VIEW logistics.buyer_delivery_tracking OWNER TO nexa_migrator;
REVOKE CREATE ON SCHEMA logistics FROM nexa_migrator;

DO $$
BEGIN
    IF pg_get_userbyid((SELECT relowner FROM pg_class
                        WHERE oid = 'logistics.buyer_delivery_tracking'::regclass)) <> 'nexa_migrator'
        OR NOT has_schema_privilege('nexa_migrator', 'logistics', 'USAGE')
        OR has_schema_privilege('nexa_migrator', 'logistics', 'CREATE')
        OR has_table_privilege('nexa_migrator', 'logistics.dispatch_order', 'SELECT')
        OR has_table_privilege('nexa_migrator', 'logistics.proof_of_delivery', 'SELECT') THEN
        RAISE EXCEPTION 'V172 view preparation did not preserve the restricted migrator boundary';
    END IF;
END;
$$;

COMMIT;
