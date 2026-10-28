-- Keep the dedicated internal-operator role and its login limited to the
-- approved metadata projection, even when new application schemas are added.
-- PostgreSQL 18 added MAINTAIN as a table privilege; operator workflows use
-- UUIDs and must not receive table-maintenance or sequence authority.
DO $$
DECLARE
    application_schemas text[];
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_internal_operator')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V179 requires the isolated operator role and login from V178';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_auth_members membership
          JOIN pg_roles operator_role ON operator_role.oid = membership.member
         WHERE operator_role.rolname = 'nexa_internal_operator'
    ) OR (
        SELECT count(*)
          FROM pg_auth_members membership
          JOIN pg_roles operator_role ON operator_role.oid = membership.roleid
          JOIN pg_roles operator_login ON operator_login.oid = membership.member
         WHERE operator_role.rolname = 'nexa_internal_operator'
           AND operator_login.rolname = 'nexa_internal_operator_login'
           AND NOT membership.inherit_option
           AND membership.set_option
           AND NOT membership.admin_option
    ) <> 1 OR EXISTS (
        SELECT 1
          FROM pg_auth_members membership
          JOIN pg_roles operator_login ON operator_login.oid = membership.member
          JOIN pg_roles operator_role ON operator_role.oid = membership.roleid
         WHERE operator_login.rolname = 'nexa_internal_operator_login'
           AND operator_role.rolname <> 'nexa_internal_operator'
    ) OR EXISTS (
        SELECT 1
          FROM pg_auth_members membership
          JOIN pg_roles operator_login ON operator_login.oid = membership.roleid
         WHERE operator_login.rolname = 'nexa_internal_operator_login'
    ) THEN
        RAISE EXCEPTION 'V179 internal-operator role membership changed from the isolated V178 contract';
    END IF;

    SELECT array_agg(schema_row.nspname ORDER BY schema_row.nspname)
      INTO application_schemas
      FROM pg_namespace schema_row
     WHERE schema_row.nspname !~ '^pg_'
       AND schema_row.nspname <> 'information_schema';

    IF EXISTS (
        SELECT 1
          FROM pg_namespace schema_row
         WHERE schema_row.nspname = ANY (application_schemas)
           AND has_schema_privilege('nexa_internal_operator', schema_row.oid, 'CREATE')
    ) OR EXISTS (
        SELECT 1
          FROM pg_namespace schema_row
         WHERE schema_row.nspname = ANY (application_schemas)
           AND schema_row.nspname NOT IN ('iam', 'tenant_management', 'public')
           AND has_schema_privilege('nexa_internal_operator', schema_row.oid, 'USAGE')
    ) THEN
        RAISE EXCEPTION 'V179 internal-operator role must have no application-schema CREATE and only approved schema usage';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname = ANY (application_schemas)
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND relation.oid NOT IN (
               'iam.internal_support_request'::regclass,
               'iam.internal_support_audit_event'::regclass,
               'tenant_management.organization_registration'::regclass,
               'tenant_management.tenant_business_database_provisioning_task'::regclass,
               'tenant_management.tenant_business_database_workspace_anchor_task'::regclass,
               'tenant_management.tenant_business_database_binding'::regclass
           )
           AND (has_table_privilege('nexa_internal_operator', relation.oid, 'SELECT')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'INSERT')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'UPDATE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'DELETE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'TRUNCATE')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'REFERENCES')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'TRIGGER')
             OR has_table_privilege('nexa_internal_operator', relation.oid, 'MAINTAIN')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'SELECT')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'INSERT')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'UPDATE')
             OR has_any_column_privilege('nexa_internal_operator', relation.oid, 'REFERENCES'))
    ) OR EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname = ANY (application_schemas)
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND has_table_privilege('nexa_internal_operator', relation.oid, 'MAINTAIN')
    ) THEN
        RAISE EXCEPTION 'V179 internal-operator role has unapproved application relation privileges';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class sequence
          JOIN pg_namespace schema_row ON schema_row.oid = sequence.relnamespace
         WHERE schema_row.nspname = ANY (application_schemas)
           AND sequence.relkind = 'S'
           AND (has_sequence_privilege('nexa_internal_operator', sequence.oid, 'USAGE')
             OR has_sequence_privilege('nexa_internal_operator', sequence.oid, 'SELECT')
             OR has_sequence_privilege('nexa_internal_operator', sequence.oid, 'UPDATE'))
    ) THEN
        RAISE EXCEPTION 'V179 internal-operator role must not have application sequence privileges';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname = ANY (application_schemas)
           AND function_row.prosecdef
           AND has_schema_privilege('nexa_internal_operator', schema_row.oid, 'USAGE')
           AND has_function_privilege('nexa_internal_operator', function_row.oid, 'EXECUTE')
    ) THEN
        RAISE EXCEPTION 'V179 internal-operator role must not execute application SECURITY DEFINER functions';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_namespace schema_row
         WHERE schema_row.nspname = ANY (application_schemas)
           AND (has_schema_privilege('nexa_internal_operator_login', schema_row.oid, 'CREATE')
             OR (schema_row.nspname NOT IN ('public')
                 AND has_schema_privilege('nexa_internal_operator_login', schema_row.oid, 'USAGE')))
    ) THEN
        RAISE EXCEPTION 'V179 internal-operator login must not access application schemas or create objects';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname = ANY (application_schemas)
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
         WHERE schema_row.nspname = ANY (application_schemas)
           AND sequence.relkind = 'S'
           AND (has_sequence_privilege('nexa_internal_operator_login', sequence.oid, 'USAGE')
             OR has_sequence_privilege('nexa_internal_operator_login', sequence.oid, 'SELECT')
             OR has_sequence_privilege('nexa_internal_operator_login', sequence.oid, 'UPDATE'))
    ) OR EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname = ANY (application_schemas)
           AND function_row.prosecdef
           AND has_function_privilege('nexa_internal_operator_login', function_row.oid, 'EXECUTE')
    ) THEN
        RAISE EXCEPTION 'V179 internal-operator login has application data or privileged function access';
    END IF;
END;
$$;
