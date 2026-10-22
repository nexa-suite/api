-- The operator GUC is caller-settable. The operator-only RLS policies therefore
-- require an isolated database principal and a one-shot owner-bound DDL helper.
DO $$
DECLARE
    migration_role pg_roles%ROWTYPE;
    operator_role pg_roles%ROWTYPE;
    login_role pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF NOT FOUND OR current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator'
        OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'V178 must run in the restricted direct nexa_migrator session';
    END IF;

    SELECT * INTO operator_role FROM pg_roles WHERE rolname = 'nexa_internal_operator';
    SELECT * INTO login_role FROM pg_roles WHERE rolname = 'nexa_internal_operator_login';
    IF operator_role.oid IS NULL OR operator_role.rolcanlogin OR operator_role.rolinherit
        OR operator_role.rolsuper OR operator_role.rolcreatedb OR operator_role.rolcreaterole
        OR operator_role.rolreplication OR operator_role.rolbypassrls
        OR login_role.oid IS NULL OR NOT login_role.rolcanlogin OR login_role.rolinherit
        OR login_role.rolsuper OR login_role.rolcreatedb OR login_role.rolcreaterole
        OR login_role.rolreplication OR login_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = operator_role.oid)
        OR (SELECT count(*) FROM pg_auth_members WHERE roleid = operator_role.oid) <> 1
        OR NOT EXISTS (SELECT 1 FROM pg_auth_members
                       WHERE roleid = operator_role.oid AND member = login_role.oid
                         AND NOT inherit_option AND set_option AND NOT admin_option)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = login_role.oid AND roleid <> operator_role.oid)
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE roleid = login_role.oid)
        OR EXISTS (SELECT 1 FROM pg_database WHERE datdba IN (operator_role.oid, login_role.oid))
        OR EXISTS (SELECT 1 FROM pg_namespace
                   WHERE nspowner IN (operator_role.oid, login_role.oid)
                      OR has_schema_privilege(operator_role.oid, oid, 'CREATE')
                      OR has_schema_privilege(login_role.oid, oid, 'CREATE'))
        OR EXISTS (SELECT 1 FROM pg_class WHERE relowner IN (operator_role.oid, login_role.oid))
        OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner IN (operator_role.oid, login_role.oid)) THEN
        RAISE EXCEPTION 'V178 requires one isolated NOLOGIN role and one NOINHERIT login with only SET ROLE membership';
    END IF;

    IF NOT has_function_privilege(current_user,
            'tenant_management.apply_v178_internal_operator_tenant_scope()', 'EXECUTE')
        OR NOT has_function_privilege(current_user,
            'iam.apply_v178_internal_operator_support_scope()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V178 requires both owner-bound one-shot table preparation helpers';
    END IF;
END;
$$;

SELECT tenant_management.apply_v178_internal_operator_tenant_scope();
SELECT iam.apply_v178_internal_operator_support_scope();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE function_row.proname IN (
                   'apply_v178_internal_operator_tenant_scope',
                   'apply_v178_internal_operator_support_scope')
           AND schema_row.nspname IN ('iam', 'tenant_management')
           AND has_function_privilege('nexa_migrator', function_row.oid, 'EXECUTE')
    ) THEN
        RAISE EXCEPTION 'V178 preparation helpers must revoke their one-shot migration grants';
    END IF;

    IF NOT has_schema_privilege('nexa_internal_operator', 'iam', 'USAGE')
        OR NOT has_schema_privilege('nexa_internal_operator', 'tenant_management', 'USAGE')
        OR has_schema_privilege('nexa_internal_operator', 'iam', 'CREATE')
        OR has_schema_privilege('nexa_internal_operator', 'tenant_management', 'CREATE')
        OR EXISTS (SELECT 1 FROM pg_namespace
                   WHERE has_schema_privilege('nexa_internal_operator', oid, 'CREATE')) THEN
        RAISE EXCEPTION 'V178 operator role must receive schema usage without schema creation privileges';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname IN ('iam', 'tenant_management')
           AND function_row.prosecdef
           AND has_schema_privilege('nexa_internal_operator', schema_row.oid, 'USAGE')
           AND has_function_privilege('nexa_internal_operator', function_row.oid, 'EXECUTE')
    ) THEN
        RAISE EXCEPTION 'V178 operator role must not execute application functions';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname IN ('iam', 'tenant_management')
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND relation.oid NOT IN (
               'iam.internal_support_request'::regclass,
               'iam.internal_support_audit_event'::regclass,
               'tenant_management.organization_registration'::regclass,
               'tenant_management.tenant_business_database_provisioning_task'::regclass,
               'tenant_management.tenant_business_database_binding'::regclass
           )
           AND (has_table_privilege('nexa_internal_operator', relation.oid, 'SELECT')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'INSERT')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'UPDATE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'DELETE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'TRUNCATE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'REFERENCES')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'TRIGGER')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'SELECT')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'INSERT')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'UPDATE')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'REFERENCES'))) THEN
        RAISE EXCEPTION 'V178 internal-operator role has access outside approved central metadata tables';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class relation
         WHERE relation.oid IN (
                   'iam.internal_support_request'::regclass,
                   'iam.internal_support_audit_event'::regclass,
                   'tenant_management.organization_registration'::regclass,
                   'tenant_management.tenant_business_database_provisioning_task'::regclass,
                   'tenant_management.tenant_business_database_workspace_anchor_task'::regclass,
                   'tenant_management.tenant_business_database_binding'::regclass)
           AND (has_table_privilege('nexa_internal_operator', relation.oid, 'SELECT')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'INSERT')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'UPDATE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'DELETE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'TRUNCATE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'REFERENCES')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'TRIGGER')
           )
    ) OR EXISTS (
        WITH expected (relation_oid, column_name, privilege_type) AS (
            VALUES
                ('tenant_management.organization_registration'::regclass, 'id', 'SELECT'),
                ('tenant_management.organization_registration'::regclass, 'status', 'SELECT'),
                ('tenant_management.organization_registration'::regclass, 'tenant_id', 'SELECT'),
                ('tenant_management.organization_registration'::regclass, 'workspace_id', 'SELECT'),
                ('tenant_management.organization_registration'::regclass, 'updated_at', 'SELECT'),
                ('tenant_management.tenant_business_database_provisioning_task'::regclass, 'tenant_id', 'SELECT'),
                ('tenant_management.tenant_business_database_provisioning_task'::regclass, 'workspace_id', 'SELECT'),
                ('tenant_management.tenant_business_database_provisioning_task'::regclass, 'status', 'SELECT'),
                ('tenant_management.tenant_business_database_provisioning_task'::regclass, 'attempt_count', 'SELECT'),
                ('tenant_management.tenant_business_database_provisioning_task'::regclass, 'updated_at', 'SELECT'),
                ('tenant_management.tenant_business_database_binding'::regclass, 'tenant_id', 'SELECT'),
                ('tenant_management.tenant_business_database_binding'::regclass, 'lifecycle_state', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'id', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'tenant_id', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'workspace_id', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'resource_type', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'resource_id', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'requested_by_operator_id', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'approved_by_operator_id', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'owner_consented_at', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'status', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'expires_at', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'created_at', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'version', 'SELECT'),
                ('iam.internal_support_request'::regclass, 'id', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'tenant_id', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'workspace_id', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'resource_type', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'resource_id', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'requested_by_operator_id', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'status', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'expires_at', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'created_at', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'updated_at', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'version', 'INSERT'),
                ('iam.internal_support_request'::regclass, 'approved_by_operator_id', 'UPDATE'),
                ('iam.internal_support_request'::regclass, 'status', 'UPDATE'),
                ('iam.internal_support_request'::regclass, 'revoked_at', 'UPDATE'),
                ('iam.internal_support_request'::regclass, 'revoked_by_operator_id', 'UPDATE'),
                ('iam.internal_support_request'::regclass, 'updated_at', 'UPDATE'),
                ('iam.internal_support_request'::regclass, 'version', 'UPDATE'),
                ('iam.internal_support_audit_event'::regclass, 'id', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'support_request_id', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'action', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'actor_type', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'actor_id', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'actor_membership_id', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'tenant_id', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'workspace_id', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'resource_type', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'resource_id', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'expires_at', 'INSERT'),
                ('iam.internal_support_audit_event'::regclass, 'occurred_at', 'INSERT')
        ),
        columns AS (
            SELECT attribute.attrelid, attribute.attname, attribute.attnum
              FROM pg_attribute attribute
             WHERE attribute.attnum > 0 AND NOT attribute.attisdropped
               AND attribute.attrelid IN (
                   'iam.internal_support_request'::regclass,
                   'iam.internal_support_audit_event'::regclass,
                   'tenant_management.organization_registration'::regclass,
                   'tenant_management.tenant_business_database_provisioning_task'::regclass,
                   'tenant_management.tenant_business_database_workspace_anchor_task'::regclass,
                   'tenant_management.tenant_business_database_binding'::regclass)
        ),
        privileges (privilege_type) AS (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('REFERENCES'))
        SELECT 1
          FROM columns
          CROSS JOIN privileges
         WHERE has_column_privilege('nexa_internal_operator', columns.attrelid,
                                    columns.attnum, privileges.privilege_type)
           AND NOT EXISTS (SELECT 1 FROM expected
                           WHERE expected.relation_oid = columns.attrelid
                             AND expected.column_name = columns.attname
                             AND expected.privilege_type = privileges.privilege_type)
        UNION ALL
        SELECT 1
          FROM expected
         WHERE NOT has_column_privilege('nexa_internal_operator', expected.relation_oid,
                                        expected.column_name, expected.privilege_type)
    ) THEN
        RAISE EXCEPTION 'V178 internal-operator column privileges do not match the approved metadata and support contract';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_namespace schema_row
         WHERE schema_row.nspname IN ('iam', 'tenant_management')
           AND (has_schema_privilege('nexa_internal_operator_login', schema_row.oid, 'USAGE')
             OR has_schema_privilege('nexa_internal_operator_login', schema_row.oid, 'CREATE'))
    ) OR EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname IN ('iam', 'tenant_management')
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND (has_table_privilege('nexa_internal_operator_login', relation.oid, 'SELECT')
             OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'INSERT')
             OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'UPDATE')
             OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'DELETE')
             OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'TRUNCATE')
             OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'REFERENCES')
             OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'TRIGGER')
             OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'MAINTAIN')
             OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'SELECT')
             OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'INSERT')
             OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'UPDATE')
             OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'REFERENCES'))
    ) OR EXISTS (
        SELECT 1
          FROM pg_class sequence
          JOIN pg_namespace schema_row ON schema_row.oid = sequence.relnamespace
         WHERE schema_row.nspname IN ('iam', 'tenant_management')
           AND sequence.relkind = 'S'
           AND (has_sequence_privilege('nexa_internal_operator_login', sequence.oid, 'USAGE')
             OR has_sequence_privilege('nexa_internal_operator_login', sequence.oid, 'SELECT')
             OR has_sequence_privilege('nexa_internal_operator_login', sequence.oid, 'UPDATE'))
    ) OR EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname IN ('iam', 'tenant_management')
           AND function_row.prosecdef
           AND has_function_privilege('nexa_internal_operator_login', function_row.oid, 'EXECUTE')
    ) THEN
        RAISE EXCEPTION 'V178 internal-operator login must have no direct access to central application schemas';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'tenant_management'
                     AND tablename = 'organization_registration'
                     AND policyname = 'organization_registration_internal_console_read'
                     AND cmd = 'SELECT'
                     AND lower(qual) LIKE '%current_user%'
                     AND lower(qual) LIKE '%nexa_internal_operator%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'tenant_business_database_provisioning_task'
                         AND policyname = 'tenant_business_database_provisioning_task_internal_operator'
                     AND cmd = 'SELECT'
                         AND lower(qual) LIKE '%current_user%'
                         AND lower(qual) LIKE '%nexa_internal_operator%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'tenant_business_database_workspace_anchor_task'
                         AND policyname = 'tenant_business_db_workspace_anchor_internal_op'
                         AND cmd = 'SELECT'
                         AND lower(qual) LIKE '%current_user%'
                         AND lower(qual) LIKE '%nexa_internal_operator%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'tenant_business_database_binding'
                         AND policyname = 'tenant_business_database_binding_internal_operator'
                     AND cmd = 'SELECT'
                         AND lower(qual) LIKE '%current_user%'
                         AND lower(qual) LIKE '%nexa_internal_operator%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'iam'
                         AND tablename = 'internal_support_request'
                         AND policyname = 'internal_support_request_verified_operator_scope'
                     AND cmd = 'ALL'
                     AND lower(qual) LIKE '%current_user%'
                     AND lower(qual) LIKE '%nexa_internal_operator%'
                     AND lower(with_check) LIKE '%current_user%'
                     AND lower(with_check) LIKE '%nexa_internal_operator%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'iam'
                         AND tablename = 'internal_support_audit_event'
                         AND policyname = 'internal_support_audit_verified_operator_scope'
                     AND cmd = 'ALL'
                     AND lower(qual) LIKE '%current_user%'
                     AND lower(qual) LIKE '%nexa_internal_operator%'
                         AND lower(with_check) LIKE '%current_user%'
                         AND lower(with_check) LIKE '%nexa_internal_operator%') THEN
        RAISE EXCEPTION 'V178 internal-operator RLS policies are missing their database-principal gate';
    END IF;
END;
$$;
