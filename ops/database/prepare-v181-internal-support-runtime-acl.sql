-- Install the single-use IAM-owner helper before the restricted V181 migration.
-- The same preparer also recovers a V181 attempt from a V180 database.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    support_owner oid;
    support_owner_super boolean;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_internal_operator',
                        'nexa_internal_operator_login', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V181 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) NOT IN (179, 180) THEN
        RAISE EXCEPTION 'V181 preparation requires central Flyway version 179 or 180 to be current';
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
        RAISE EXCEPTION 'V181 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.relowner INTO support_owner
      FROM pg_class relation
      JOIN pg_namespace ns ON ns.oid = relation.relnamespace
     WHERE ns.nspname = 'iam'
       AND relation.relname = 'internal_support_request'
       AND relation.relkind = 'r';
    IF support_owner IS NULL OR (SELECT relowner FROM pg_class
                                  WHERE oid = 'iam.internal_support_audit_event'::regclass)
            IS DISTINCT FROM support_owner THEN
        RAISE EXCEPTION 'V181 requires both internal support tables to share one owner';
    END IF;
    IF support_owner = runtime_role.oid OR pg_has_role(runtime_role.oid, support_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'nexa_runtime must not own or inherit the internal support table owner';
    END IF;

    SELECT rolsuper INTO support_owner_super FROM pg_roles WHERE oid = support_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> support_owner
        AND NOT pg_has_role(current_role_oid, support_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V181 preparation must run as the support-table owner or an authorized database administrator';
    END IF;
    IF NOT support_owner_super
        AND (NOT has_schema_privilege(support_owner, 'iam', 'USAGE')
            OR NOT has_schema_privilege(support_owner, 'iam', 'CREATE')) THEN
        RAISE EXCEPTION 'The support-table owner must be able to own the one-shot IAM helper';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION iam.apply_v181_internal_support_runtime_acl()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    request_oid oid;
    audit_oid oid;
    support_owner oid;
    column_name name;
BEGIN
    SELECT oid, relowner INTO request_oid, support_owner
      FROM pg_class WHERE oid = 'iam.internal_support_request'::regclass;
    SELECT oid INTO audit_oid FROM pg_class WHERE oid = 'iam.internal_support_audit_event'::regclass;
    IF session_user <> 'nexa_migrator'
        OR request_oid IS NULL OR audit_oid IS NULL
        OR current_user <> pg_get_userbyid(support_owner)
        OR (SELECT relowner FROM pg_class WHERE oid = audit_oid) <> support_owner THEN
        RAISE EXCEPTION 'V181 ACL change may only run as nexa_migrator under the support-table owner';
    END IF;

    EXECUTE 'REVOKE ALL PRIVILEGES ON TABLE iam.internal_support_request, '
        || 'iam.internal_support_audit_event FROM PUBLIC, nexa_runtime';
    FOR column_name IN
        SELECT attname
          FROM pg_attribute
         WHERE attrelid IN (request_oid, audit_oid)
           AND attnum > 0
           AND NOT attisdropped
    LOOP
        IF EXISTS (SELECT 1 FROM pg_attribute
                   WHERE attrelid = request_oid AND attname = column_name
                     AND attnum > 0 AND NOT attisdropped) THEN
            EXECUTE format('REVOKE ALL PRIVILEGES (%I) ON TABLE iam.internal_support_request '
                || 'FROM PUBLIC, nexa_runtime', column_name);
        END IF;
        IF EXISTS (SELECT 1 FROM pg_attribute
                   WHERE attrelid = audit_oid AND attname = column_name
                     AND attnum > 0 AND NOT attisdropped) THEN
            EXECUTE format('REVOKE ALL PRIVILEGES (%I) ON TABLE iam.internal_support_audit_event '
                || 'FROM PUBLIC, nexa_runtime', column_name);
        END IF;
    END LOOP;

    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, resource_type, resource_id, requested_by_operator_id, '
        || 'approved_by_operator_id, status, expires_at, created_at, version) '
        || 'ON iam.internal_support_request TO nexa_runtime';
    EXECUTE 'GRANT UPDATE (owner_consent_user_id, owner_consent_membership_id, owner_consented_at, '
        || 'status, revoked_at, revoked_by_owner_user_id, revoked_by_owner_membership_id, updated_at, version) '
        || 'ON iam.internal_support_request TO nexa_runtime';
    EXECUTE 'GRANT INSERT (id, support_request_id, action, actor_type, actor_id, actor_membership_id, '
        || 'tenant_id, workspace_id, resource_type, resource_id, expires_at, occurred_at) '
        || 'ON iam.internal_support_audit_event TO nexa_runtime';

    EXECUTE 'DROP POLICY IF EXISTS internal_support_audit_owner_insert_scope '
        || 'ON iam.internal_support_audit_event';
    EXECUTE 'CREATE POLICY internal_support_audit_owner_insert_scope '
        || 'ON iam.internal_support_audit_event FOR INSERT TO nexa_runtime '
        || 'WITH CHECK (tenant_id::text = nullif(current_setting(''app.current_tenant_id'', true), '''') '
        || 'AND workspace_id::text = nullif(current_setting(''app.current_workspace_id'', true), '''') '
        || 'AND actor_type = ''COMPANY_OWNER'' '
        || 'AND action IN (''OWNER_CONSENTED'', ''REVOKED''))';

    IF has_table_privilege('nexa_runtime', request_oid, 'SELECT')
        OR has_table_privilege('nexa_runtime', request_oid, 'INSERT')
        OR has_table_privilege('nexa_runtime', request_oid, 'UPDATE')
        OR has_table_privilege('nexa_runtime', request_oid, 'DELETE')
        OR has_table_privilege('nexa_runtime', request_oid, 'TRUNCATE')
        OR has_any_column_privilege('nexa_runtime', request_oid, 'INSERT')
        OR has_column_privilege('nexa_runtime', request_oid, 'approved_by_operator_id', 'UPDATE')
        OR has_column_privilege('nexa_runtime', request_oid, 'revoked_by_operator_id', 'UPDATE') THEN
        RAISE EXCEPTION 'V181 runtime retains forbidden internal-operator support-request privileges';
    END IF;
    IF NOT has_column_privilege('nexa_runtime', request_oid, 'id', 'SELECT')
        OR NOT has_column_privilege('nexa_runtime', request_oid, 'status', 'SELECT')
        OR NOT has_column_privilege('nexa_runtime', request_oid, 'status', 'UPDATE')
        OR NOT has_column_privilege('nexa_runtime', request_oid, 'owner_consented_at', 'UPDATE')
        OR NOT has_any_column_privilege('nexa_runtime', request_oid, 'SELECT') THEN
        RAISE EXCEPTION 'V181 removed required Company Owner support-request access';
    END IF;

    IF has_table_privilege('nexa_runtime', audit_oid, 'SELECT')
        OR has_any_column_privilege('nexa_runtime', audit_oid, 'SELECT')
        OR has_table_privilege('nexa_runtime', audit_oid, 'INSERT')
        OR has_table_privilege('nexa_runtime', audit_oid, 'UPDATE')
        OR has_table_privilege('nexa_runtime', audit_oid, 'DELETE')
        OR has_table_privilege('nexa_runtime', audit_oid, 'TRUNCATE')
        OR has_any_column_privilege('nexa_runtime', audit_oid, 'UPDATE')
        OR NOT has_column_privilege('nexa_runtime', audit_oid, 'actor_type', 'INSERT') THEN
        RAISE EXCEPTION 'V181 runtime support-audit privileges must be append-only and write-only';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'iam' AND tablename = 'internal_support_audit_event'
                     AND policyname = 'internal_support_audit_owner_insert_scope' AND cmd = 'INSERT'
                     AND roles = ARRAY['nexa_runtime']::name[]
                     AND with_check LIKE '%app.current_tenant_id%'
                     AND with_check LIKE '%app.current_workspace_id%'
                     AND with_check LIKE '%COMPANY_OWNER%'
                     AND with_check LIKE '%OWNER_CONSENTED%'
                     AND with_check LIKE '%REVOKED%') THEN
        RAISE EXCEPTION 'V181 runtime support-audit policy must bind owner actions to Tenant/Workspace scope';
    END IF;

    EXECUTE 'REVOKE ALL ON FUNCTION iam.apply_v181_internal_support_runtime_acl() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    support_owner oid;
    function_grantee record;
    helper_oid oid;
BEGIN
    SELECT relowner INTO support_owner FROM pg_class WHERE oid = 'iam.internal_support_request'::regclass;
    helper_oid := 'iam.apply_v181_internal_support_runtime_acl()'::regprocedure;

    EXECUTE format('ALTER FUNCTION iam.apply_v181_internal_support_runtime_acl() OWNER TO %I',
        pg_get_userbyid(support_owner));

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
            EXECUTE 'REVOKE ALL ON FUNCTION iam.apply_v181_internal_support_runtime_acl() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION iam.apply_v181_internal_support_runtime_acl() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION iam.apply_v181_internal_support_runtime_acl() TO nexa_migrator;
END;
$$;

COMMIT;
