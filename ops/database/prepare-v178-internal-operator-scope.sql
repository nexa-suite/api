-- Run as an authorized database owner after V177 and before V178.
-- Installs fixed, one-shot helpers for the existing split table ownership.
BEGIN;

DO $$
DECLARE
    migration_role pg_roles%ROWTYPE;
    operator_role pg_roles%ROWTYPE;
    operator_login pg_roles%ROWTYPE;
    tenant_owner oid;
    support_owner oid;
    tenant_schema_owner oid;
    iam_schema_owner oid;
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_internal_operator',
                        'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V178 preparation must run outside application and migration sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 177 THEN
        RAISE EXCEPTION 'V178 preparation requires central Flyway version 177 to be current';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    SELECT * INTO operator_role FROM pg_roles WHERE rolname = 'nexa_internal_operator';
    SELECT * INTO operator_login FROM pg_roles WHERE rolname = 'nexa_internal_operator_login';
    IF migration_role.oid IS NULL OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'nexa_migrator must remain a restricted direct login without role memberships';
    END IF;

    IF operator_role.oid IS NULL OR operator_role.rolcanlogin OR operator_role.rolinherit
        OR operator_role.rolsuper OR operator_role.rolcreatedb OR operator_role.rolcreaterole
        OR operator_role.rolreplication OR operator_role.rolbypassrls
        OR operator_login.oid IS NULL OR NOT operator_login.rolcanlogin OR operator_login.rolinherit
        OR operator_login.rolsuper OR operator_login.rolcreatedb OR operator_login.rolcreaterole
        OR operator_login.rolreplication OR operator_login.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = operator_role.oid)
        OR (SELECT count(*) FROM pg_auth_members WHERE roleid = operator_role.oid) <> 1
        OR NOT EXISTS (SELECT 1 FROM pg_auth_members
                       WHERE roleid = operator_role.oid AND member = operator_login.oid
                         AND NOT inherit_option AND set_option AND NOT admin_option)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = operator_login.oid AND roleid <> operator_role.oid)
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE roleid = operator_login.oid)
        OR EXISTS (SELECT 1 FROM pg_database WHERE datdba IN (operator_role.oid, operator_login.oid))
        OR EXISTS (SELECT 1 FROM pg_namespace
                   WHERE nspowner IN (operator_role.oid, operator_login.oid)
                      OR has_schema_privilege(operator_role.oid, oid, 'CREATE')
                      OR has_schema_privilege(operator_login.oid, oid, 'CREATE'))
        OR EXISTS (SELECT 1 FROM pg_class WHERE relowner IN (operator_role.oid, operator_login.oid))
        OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner IN (operator_role.oid, operator_login.oid)) THEN
        RAISE EXCEPTION 'V178 requires one isolated NOLOGIN role and one NOINHERIT login with only SET ROLE membership';
    END IF;

    SELECT relowner INTO tenant_owner
      FROM pg_class WHERE oid = 'tenant_management.organization_registration'::regclass;
    IF tenant_owner IS NULL OR EXISTS (
        SELECT 1 FROM pg_class
         WHERE oid IN (
             'tenant_management.tenant_business_database_provisioning_task'::regclass,
             'tenant_management.tenant_business_database_workspace_anchor_task'::regclass,
             'tenant_management.tenant_business_database_binding'::regclass)
           AND relowner <> tenant_owner
    ) THEN
        RAISE EXCEPTION 'V178 requires the onboarding tables to share one owner';
    END IF;

    SELECT relowner INTO support_owner
      FROM pg_class WHERE oid = 'iam.internal_support_request'::regclass;
    IF support_owner IS NULL
        OR (SELECT relowner FROM pg_class WHERE oid = 'iam.internal_support_audit_event'::regclass)
            IS DISTINCT FROM support_owner THEN
        RAISE EXCEPTION 'V178 requires the internal support tables to share one owner';
    END IF;

    SELECT nspowner INTO tenant_schema_owner FROM pg_namespace WHERE nspname = 'tenant_management';
    SELECT nspowner INTO iam_schema_owner FROM pg_namespace WHERE nspname = 'iam';
    IF tenant_schema_owner IS NULL OR iam_schema_owner IS NULL THEN
        RAISE EXCEPTION 'V178 requires the IAM and Tenant Management schemas';
    END IF;

    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super
        AND (NOT pg_has_role(current_role_oid, tenant_owner, 'MEMBER')
          OR NOT pg_has_role(current_role_oid, support_owner, 'MEMBER')
          OR NOT pg_has_role(current_role_oid, tenant_schema_owner, 'MEMBER')
          OR NOT pg_has_role(current_role_oid, iam_schema_owner, 'MEMBER')
          OR NOT has_schema_privilege(current_role_oid, 'tenant_management', 'CREATE')
          OR NOT has_schema_privilege(current_role_oid, 'iam', 'CREATE')) THEN
        RAISE EXCEPTION 'V178 preparation requires an authorized owner for both tables and schemas';
    END IF;

    IF NOT pg_has_role('nexa_migrator', tenant_owner, 'MEMBER')
        AND tenant_owner <> migration_role.oid THEN
        RAISE EXCEPTION 'V178 migration login must be able to execute the tenant owner helper';
    END IF;
END;
$$;

-- Schema USAGE is not data access. The authorized preparer establishes it
-- before the restricted migrator invokes the IAM table-owner helper.
GRANT USAGE ON SCHEMA tenant_management, iam TO nexa_migrator;
GRANT USAGE ON SCHEMA tenant_management, iam TO nexa_internal_operator;

CREATE OR REPLACE FUNCTION tenant_management.apply_v178_internal_operator_tenant_scope()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    owner_oid oid;
BEGIN
    SELECT relowner INTO owner_oid
      FROM pg_class WHERE oid = 'tenant_management.organization_registration'::regclass;
    IF session_user <> 'nexa_migrator'
        OR current_user <> pg_get_userbyid(owner_oid)
        OR EXISTS (
            SELECT 1 FROM pg_class
             WHERE oid IN (
                 'tenant_management.tenant_business_database_provisioning_task'::regclass,
                 'tenant_management.tenant_business_database_workspace_anchor_task'::regclass,
                 'tenant_management.tenant_business_database_binding'::regclass)
               AND relowner <> owner_oid
        ) THEN
        RAISE EXCEPTION 'V178 Tenant scope helper must run as the shared onboarding table owner';
    END IF;

    EXECUTE 'GRANT SELECT (id, status, tenant_id, workspace_id, updated_at) '
        || 'ON tenant_management.organization_registration TO nexa_internal_operator';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, status, attempt_count, updated_at) '
        || 'ON tenant_management.tenant_business_database_provisioning_task TO nexa_internal_operator';
    EXECUTE 'GRANT SELECT (tenant_id, lifecycle_state) '
        || 'ON tenant_management.tenant_business_database_binding TO nexa_internal_operator';

    EXECUTE 'DROP POLICY organization_registration_internal_console_read '
        || 'ON tenant_management.organization_registration';
    EXECUTE 'CREATE POLICY organization_registration_internal_console_read '
        || 'ON tenant_management.organization_registration FOR SELECT '
        || 'USING (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'')';

    EXECUTE 'DROP POLICY tenant_business_database_provisioning_task_internal_operator '
        || 'ON tenant_management.tenant_business_database_provisioning_task';
    EXECUTE 'CREATE POLICY tenant_business_database_provisioning_task_internal_operator '
        || 'ON tenant_management.tenant_business_database_provisioning_task FOR SELECT '
        || 'USING (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'')';

    -- V154's longer identifier was truncated by PostgreSQL to this name.
    EXECUTE 'DROP POLICY IF EXISTS tenant_business_database_workspace_anchor_task_internal_operato '
        || 'ON tenant_management.tenant_business_database_workspace_anchor_task';
    EXECUTE 'DROP POLICY IF EXISTS tenant_business_db_workspace_anchor_internal_op '
        || 'ON tenant_management.tenant_business_database_workspace_anchor_task';
    EXECUTE 'CREATE POLICY tenant_business_db_workspace_anchor_internal_op '
        || 'ON tenant_management.tenant_business_database_workspace_anchor_task FOR SELECT '
        || 'USING (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'')';

    EXECUTE 'DROP POLICY tenant_business_database_binding_internal_operator '
        || 'ON tenant_management.tenant_business_database_binding';
    EXECUTE 'CREATE POLICY tenant_business_database_binding_internal_operator '
        || 'ON tenant_management.tenant_business_database_binding FOR SELECT '
        || 'USING (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'')';

    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v178_internal_operator_tenant_scope() FROM nexa_migrator';
END;
$function$;

CREATE OR REPLACE FUNCTION iam.apply_v178_internal_operator_support_scope()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    owner_oid oid;
BEGIN
    SELECT relowner INTO owner_oid
      FROM pg_class WHERE oid = 'iam.internal_support_request'::regclass;
    IF session_user <> 'nexa_migrator'
        OR current_user <> pg_get_userbyid(owner_oid)
        OR (SELECT relowner FROM pg_class WHERE oid = 'iam.internal_support_audit_event'::regclass)
            IS DISTINCT FROM owner_oid THEN
        RAISE EXCEPTION 'V178 support scope helper must run as the shared support table owner';
    END IF;

    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, resource_type, resource_id, requested_by_operator_id, '
        || 'approved_by_operator_id, owner_consented_at, status, expires_at, created_at, version) '
        || 'ON iam.internal_support_request TO nexa_internal_operator';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, resource_type, resource_id, requested_by_operator_id, '
        || 'status, expires_at, created_at, updated_at, version) '
        || 'ON iam.internal_support_request TO nexa_internal_operator';
    EXECUTE 'GRANT UPDATE (approved_by_operator_id, status, revoked_at, revoked_by_operator_id, updated_at, version) '
        || 'ON iam.internal_support_request TO nexa_internal_operator';
    EXECUTE 'GRANT INSERT (id, support_request_id, action, actor_type, actor_id, actor_membership_id, tenant_id, '
        || 'workspace_id, resource_type, resource_id, expires_at, occurred_at) '
        || 'ON iam.internal_support_audit_event TO nexa_internal_operator';

    EXECUTE 'DROP POLICY internal_support_request_verified_operator_scope ON iam.internal_support_request';
    EXECUTE 'CREATE POLICY internal_support_request_verified_operator_scope '
        || 'ON iam.internal_support_request FOR ALL '
        || 'USING (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'') '
        || 'WITH CHECK (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'')';

    EXECUTE 'DROP POLICY internal_support_audit_verified_operator_scope ON iam.internal_support_audit_event';
    EXECUTE 'CREATE POLICY internal_support_audit_verified_operator_scope '
        || 'ON iam.internal_support_audit_event FOR ALL '
        || 'USING (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'') '
        || 'WITH CHECK (current_user = ''nexa_internal_operator'' '
        || 'AND nullif(current_setting(''app.current_internal_operator_id'', true), '''') '
        || '~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'')';

    EXECUTE 'REVOKE EXECUTE ON FUNCTION iam.apply_v178_internal_operator_support_scope() FROM nexa_migrator';
END;
$function$;

DO $$
DECLARE
    tenant_owner oid;
    support_owner oid;
    tenant_schema_owner oid;
    iam_schema_owner oid;
BEGIN
    SELECT relowner INTO tenant_owner
      FROM pg_class WHERE oid = 'tenant_management.organization_registration'::regclass;
    SELECT relowner INTO support_owner
      FROM pg_class WHERE oid = 'iam.internal_support_request'::regclass;

    SELECT nspowner INTO tenant_schema_owner FROM pg_namespace WHERE nspname = 'tenant_management';
    SELECT nspowner INTO iam_schema_owner FROM pg_namespace WHERE nspname = 'iam';

    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v178_internal_operator_tenant_scope() FROM PUBLIC';
    EXECUTE 'GRANT EXECUTE ON FUNCTION tenant_management.apply_v178_internal_operator_tenant_scope() TO nexa_migrator';
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v178_internal_operator_tenant_scope() OWNER TO %I',
        pg_get_userbyid(tenant_owner));

    EXECUTE 'REVOKE ALL ON FUNCTION iam.apply_v178_internal_operator_support_scope() FROM PUBLIC';
    EXECUTE 'GRANT EXECUTE ON FUNCTION iam.apply_v178_internal_operator_support_scope() TO nexa_migrator';
    EXECUTE format('ALTER FUNCTION iam.apply_v178_internal_operator_support_scope() OWNER TO %I',
        pg_get_userbyid(support_owner));
END;
$$;

COMMIT;
