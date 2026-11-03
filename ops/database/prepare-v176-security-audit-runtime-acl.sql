-- Run as an authorized database owner before Flyway applies V176.
-- This installs a fixed one-shot helper because nexa_migrator is not the owner
-- of iam.security_audit_event and must stay a restricted direct-login role.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    audit_owner oid;
    audit_owner_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V176 preparation must run outside runtime, migrator, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) NOT IN (174, 175) THEN
        RAISE EXCEPTION 'V176 preparation requires central Flyway version 174 or 175 to be current';
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
        RAISE EXCEPTION 'V176 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relowner INTO audit_owner
      FROM pg_class
     WHERE oid = to_regclass('iam.security_audit_event')
       AND relkind = 'r';
    IF audit_owner IS NULL THEN
        RAISE EXCEPTION 'V176 requires iam.security_audit_event';
    END IF;
    SELECT rolsuper INTO audit_owner_super FROM pg_roles WHERE oid = audit_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> audit_owner
        AND NOT pg_has_role(current_role_oid, audit_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V176 preparation must run as the Security Audit table owner or an authorized member';
    END IF;
    IF NOT audit_owner_super
        AND NOT has_schema_privilege(audit_owner, 'tenant_management', 'CREATE') THEN
        RAISE EXCEPTION 'The Security Audit table owner must be able to own the V176 helper in tenant_management';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v176_security_audit_runtime_acl()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    audit_owner oid;
BEGIN
    SELECT relowner INTO audit_owner
      FROM pg_class
     WHERE oid = to_regclass('iam.security_audit_event')
       AND relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR audit_owner IS NULL
        OR current_user <> pg_get_userbyid(audit_owner) THEN
        RAISE EXCEPTION 'V176 Security Audit ACL may only be applied by nexa_migrator under the table owner';
    END IF;

    EXECUTE 'REVOKE SELECT, UPDATE, DELETE ON TABLE iam.security_audit_event FROM nexa_runtime';
    EXECUTE 'GRANT INSERT ON TABLE iam.security_audit_event TO nexa_runtime';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v176_security_audit_runtime_acl() FROM nexa_migrator';

    IF has_table_privilege('nexa_runtime', 'iam.security_audit_event', 'SELECT')
        OR has_any_column_privilege('nexa_runtime', 'iam.security_audit_event', 'SELECT')
        OR NOT has_table_privilege('nexa_runtime', 'iam.security_audit_event', 'INSERT')
        OR has_table_privilege('nexa_runtime', 'iam.security_audit_event', 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', 'iam.security_audit_event', 'UPDATE')
        OR has_table_privilege('nexa_runtime', 'iam.security_audit_event', 'DELETE') THEN
        RAISE EXCEPTION 'V176 failed to restrict runtime Security Audit privileges to INSERT';
    END IF;

    IF has_function_privilege('nexa_migrator',
            'tenant_management.apply_v176_security_audit_runtime_acl()', 'EXECUTE')
        OR has_function_privilege('nexa_runtime',
            'tenant_management.apply_v176_security_audit_runtime_acl()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V176 one-shot Security Audit ACL helper remains executable';
    END IF;
END;
$function$;

DO $$
DECLARE
    audit_owner oid;
BEGIN
    SELECT relowner INTO audit_owner
      FROM pg_class
     WHERE oid = 'iam.security_audit_event'::regclass;
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v176_security_audit_runtime_acl() OWNER TO %I',
        pg_get_userbyid(audit_owner));
END;
$$;

DO $$
DECLARE
    function_row record;
    function_grantee record;
BEGIN
    SELECT proowner, oid
      INTO function_row
      FROM pg_proc
     WHERE oid = 'tenant_management.apply_v176_security_audit_runtime_acl()'::regprocedure;

    -- CREATE OR REPLACE preserves an existing function ACL. Normalize all
    -- non-owner grants on rerun, then grant the one migration execution.
    FOR function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = function_row.oid
           AND acl.grantee <> function_row.proowner
           AND acl.grantee <> (SELECT oid FROM pg_roles WHERE rolname = 'nexa_migrator')
    LOOP
        IF function_grantee.grantee = 0 THEN
            REVOKE ALL ON FUNCTION tenant_management.apply_v176_security_audit_runtime_acl() FROM PUBLIC;
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v176_security_audit_runtime_acl() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    REVOKE ALL ON FUNCTION tenant_management.apply_v176_security_audit_runtime_acl() FROM PUBLIC;
    GRANT EXECUTE ON FUNCTION tenant_management.apply_v176_security_audit_runtime_acl() TO nexa_migrator;
    REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION tenant_management.apply_v176_security_audit_runtime_acl()
        FROM nexa_migrator;
END;
$$;

COMMIT;
