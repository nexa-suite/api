-- Global Workspace enumeration is a metadata-only capability. The caller's
-- GUC is not authority: only the transaction-local privilege role may scan.
DO $$
DECLARE
    migration_role pg_roles%ROWTYPE;
    reader_role pg_roles%ROWTYPE;
    login_role pg_roles%ROWTYPE;
    old_workspace_scan_policy text;
BEGIN
    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF NOT FOUND OR current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator'
        OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'V175 must run in the restricted direct nexa_migrator session';
    END IF;

    SELECT * INTO reader_role FROM pg_roles WHERE rolname = 'nexa_workspace_scope_reader';
    SELECT * INTO login_role FROM pg_roles WHERE rolname = 'nexa_workspace_scope_reader_login';
    IF reader_role.oid IS NULL OR reader_role.rolcanlogin OR reader_role.rolinherit
        OR reader_role.rolsuper OR reader_role.rolcreatedb OR reader_role.rolcreaterole
        OR reader_role.rolreplication OR reader_role.rolbypassrls
        OR login_role.oid IS NULL OR NOT login_role.rolcanlogin OR login_role.rolinherit
        OR login_role.rolsuper OR login_role.rolcreatedb OR login_role.rolcreaterole
        OR login_role.rolreplication OR login_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = reader_role.oid)
        OR (SELECT count(*) FROM pg_auth_members WHERE roleid = reader_role.oid) <> 1
        OR NOT EXISTS (SELECT 1 FROM pg_auth_members
                       WHERE roleid = reader_role.oid AND member = login_role.oid
                         AND NOT inherit_option AND set_option AND NOT admin_option)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = login_role.oid AND roleid <> reader_role.oid)
        OR EXISTS (SELECT 1 FROM pg_database
                   WHERE datdba IN (reader_role.oid, login_role.oid))
        OR EXISTS (SELECT 1 FROM pg_namespace
                   WHERE nspowner IN (reader_role.oid, login_role.oid)
                      OR has_schema_privilege(reader_role.oid, oid, 'CREATE')
                      OR has_schema_privilege(login_role.oid, oid, 'CREATE'))
        OR EXISTS (SELECT 1 FROM pg_class
                   WHERE relowner IN (reader_role.oid, login_role.oid))
        OR EXISTS (SELECT 1 FROM pg_proc
                   WHERE proowner IN (reader_role.oid, login_role.oid)) THEN
        RAISE EXCEPTION 'V175 requires one isolated NOLOGIN scope role and one NOINHERIT login with only SET ROLE membership';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
           AND has_schema_privilege(reader_role.oid, schema_row.oid, 'USAGE')
           AND has_function_privilege(reader_role.oid, function_row.oid, 'EXECUTE')
           AND NOT (
               schema_row.nspname = 'public'
               AND NOT function_row.prosecdef
               AND EXISTS (
                   SELECT 1
                     FROM pg_depend extension_dependency
                     JOIN pg_extension extension_row
                       ON extension_row.oid = extension_dependency.refobjid
                    WHERE extension_dependency.classid = 'pg_proc'::regclass
                      AND extension_dependency.objid = function_row.oid
                      AND extension_dependency.refclassid = 'pg_extension'::regclass
                      AND extension_dependency.deptype = 'e'
                      AND extension_row.extname = 'btree_gist'
               )
           )
    ) OR EXISTS (
        SELECT 1
          FROM pg_proc function_row
          JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
         WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
           AND has_schema_privilege(login_role.oid, schema_row.oid, 'USAGE')
           AND has_function_privilege(login_role.oid, function_row.oid, 'EXECUTE')
           AND NOT (
               schema_row.nspname = 'public'
               AND NOT function_row.prosecdef
               AND EXISTS (
                   SELECT 1
                     FROM pg_depend extension_dependency
                     JOIN pg_extension extension_row
                       ON extension_row.oid = extension_dependency.refobjid
                    WHERE extension_dependency.classid = 'pg_proc'::regclass
                      AND extension_dependency.objid = function_row.oid
                      AND extension_dependency.refclassid = 'pg_extension'::regclass
                      AND extension_dependency.deptype = 'e'
                      AND extension_row.extname = 'btree_gist'
               )
           )
    ) THEN
        RAISE EXCEPTION 'V175 reader roles must not execute accessible application functions except btree_gist operator support';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_class
                   WHERE oid = 'tenant_management.workspace'::regclass
                     AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class
                       WHERE oid = 'tenant_management.tenant'::regclass
                         AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class
                       WHERE oid = 'tenant_management.workspace_membership'::regclass
                         AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace'
                         AND policyname = 'v102_workspace_cross_scope_scan'
                         AND cmd = 'SELECT')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'tenant'
                         AND policyname = 'v102_tenant_root_visible_workspace_read'
                         AND cmd = 'SELECT')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace'
                         AND policyname = 'business_documents_scope_reader_ready_workspace'
                         AND permissive = 'RESTRICTIVE'
                         AND cmd = 'SELECT')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace_membership'
                         AND policyname = 'v173_workspace_membership_identity_read'
                         AND cmd = 'SELECT'
                         AND qual LIKE '%app.access_context_user_id%'
                         AND qual LIKE '%app.workspace_membership_lookup_id%'
                         AND qual LIKE '%app.workspace_membership_lookup_user_id%')
        OR NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE schemaname = 'tenant_management'
                         AND tablename = 'workspace_membership'
                         AND policyname = 'v174_workspace_membership_tenant_workspace_scope'
                         AND cmd = 'ALL'
                         AND qual LIKE '%app.current_tenant_id%'
                         AND qual LIKE '%app.current_workspace_id%'
                         AND with_check LIKE '%app.current_tenant_id%'
                         AND with_check LIKE '%app.current_workspace_id%')
        OR EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'tenant_management'
                     AND tablename = 'workspace_membership'
                     AND policyname = 'v175_workspace_scope_reader_block_membership')
        OR EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'tenant_management'
                     AND ((tablename = 'workspace' AND policyname = 'v175_workspace_scope_reader_scan')
                       OR (tablename = 'tenant' AND policyname = 'v175_tenant_scope_reader_scan'))) THEN
        RAISE EXCEPTION 'V175 requires V102 forced Tenant/Workspace RLS and its cross-scope scan policy';
    END IF;

    SELECT qual INTO old_workspace_scan_policy
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'workspace'
       AND policyname = 'v102_workspace_cross_scope_scan'
       AND cmd = 'SELECT';
    IF old_workspace_scan_policy NOT LIKE '%app.cross_scope_workspace_scan%' THEN
        RAISE EXCEPTION 'V175 found an unexpected preexisting Workspace scan policy';
    END IF;

    IF has_table_privilege(reader_role.oid, 'tenant_management.workspace', 'SELECT')
        OR has_table_privilege(reader_role.oid, 'tenant_management.tenant', 'SELECT')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace', 'INSERT')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace', 'UPDATE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace', 'DELETE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace', 'TRUNCATE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace', 'REFERENCES')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace', 'TRIGGER')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace_membership', 'SELECT')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace_membership', 'INSERT')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace_membership', 'UPDATE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace_membership', 'DELETE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace_membership', 'TRUNCATE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace_membership', 'REFERENCES')
        OR has_table_privilege(reader_role.oid, 'tenant_management.workspace_membership', 'TRIGGER')
        OR has_table_privilege(reader_role.oid, 'tenant_management.tenant', 'INSERT')
        OR has_table_privilege(reader_role.oid, 'tenant_management.tenant', 'UPDATE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.tenant', 'DELETE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.tenant', 'TRUNCATE')
        OR has_table_privilege(reader_role.oid, 'tenant_management.tenant', 'REFERENCES')
        OR has_table_privilege(reader_role.oid, 'tenant_management.tenant', 'TRIGGER')
        OR has_table_privilege(reader_role.oid, 'sales.client_account', 'SELECT')
        OR has_table_privilege(reader_role.oid, 'sales.client_account', 'INSERT')
        OR has_table_privilege(reader_role.oid, 'sales.client_account', 'UPDATE')
        OR has_table_privilege(reader_role.oid, 'sales.client_account', 'DELETE')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace', 'SELECT')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace', 'INSERT')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace', 'UPDATE')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace', 'DELETE')
        OR has_table_privilege(login_role.oid, 'tenant_management.tenant', 'SELECT')
        OR has_table_privilege(login_role.oid, 'tenant_management.tenant', 'INSERT')
        OR has_table_privilege(login_role.oid, 'tenant_management.tenant', 'UPDATE')
        OR has_table_privilege(login_role.oid, 'tenant_management.tenant', 'DELETE')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace_membership', 'SELECT')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace_membership', 'INSERT')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace_membership', 'UPDATE')
        OR has_table_privilege(login_role.oid, 'tenant_management.workspace_membership', 'DELETE') THEN
        RAISE EXCEPTION 'V175 reader roles have broader table privileges than the approved metadata columns';
    END IF;

    FOR old_workspace_scan_policy IN
        SELECT format('%I.%I.%I', schema_row.nspname, relation.relname, attribute.attname)
          FROM pg_attribute attribute
          JOIN pg_class relation ON relation.oid = attribute.attrelid
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE attribute.attnum > 0 AND NOT attribute.attisdropped
           AND ((relation.oid = 'tenant_management.workspace'::regclass
                 AND attribute.attname NOT IN ('tenant_id', 'id', 'status'))
             OR (relation.oid = 'tenant_management.tenant'::regclass
                 AND attribute.attname NOT IN ('id', 'status'))
             OR (relation.oid = 'tenant_management.workspace_membership'::regclass
                 AND attribute.attname NOT IN ('tenant_id', 'id', 'workspace_id', 'user_id')))
           AND (has_column_privilege(reader_role.oid, relation.oid, attribute.attnum, 'SELECT')
             OR has_column_privilege(reader_role.oid, relation.oid, attribute.attnum, 'INSERT')
             OR has_column_privilege(reader_role.oid, relation.oid, attribute.attnum, 'UPDATE')
             OR has_column_privilege(reader_role.oid, relation.oid, attribute.attnum, 'REFERENCES'))
    LOOP
        RAISE EXCEPTION 'V175 reader role has an out-of-scope column grant on %', old_workspace_scan_policy;
    END LOOP;

    IF EXISTS (
        SELECT 1
          FROM pg_attribute attribute
         WHERE attribute.attnum > 0 AND NOT attribute.attisdropped
           AND attribute.attrelid IN ('tenant_management.workspace'::regclass,
                                      'tenant_management.tenant'::regclass,
                                      'tenant_management.workspace_membership'::regclass)
           AND (has_column_privilege(reader_role.oid, attribute.attrelid, attribute.attnum, 'INSERT')
             OR has_column_privilege(reader_role.oid, attribute.attrelid, attribute.attnum, 'UPDATE')
             OR has_column_privilege(reader_role.oid, attribute.attrelid, attribute.attnum, 'REFERENCES')
             OR has_column_privilege(login_role.oid, attribute.attrelid, attribute.attnum, 'SELECT')
             OR has_column_privilege(login_role.oid, attribute.attrelid, attribute.attnum, 'INSERT')
             OR has_column_privilege(login_role.oid, attribute.attrelid, attribute.attnum, 'UPDATE')
             OR has_column_privilege(login_role.oid, attribute.attrelid, attribute.attnum, 'REFERENCES'))
    ) THEN
        RAISE EXCEPTION 'V175 reader roles must not have column write grants or direct login column access';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
           AND schema_row.nspname NOT LIKE 'pg_toast%'
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND relation.oid NOT IN ('tenant_management.workspace'::regclass,
                                    'tenant_management.tenant'::regclass,
                                    'tenant_management.workspace_membership'::regclass)
           AND (has_table_privilege(reader_role.oid, relation.oid, 'SELECT')
             OR has_table_privilege(reader_role.oid, relation.oid, 'INSERT')
             OR has_table_privilege(reader_role.oid, relation.oid, 'UPDATE')
             OR has_table_privilege(reader_role.oid, relation.oid, 'DELETE')
             OR has_table_privilege(reader_role.oid, relation.oid, 'TRUNCATE')
             OR has_table_privilege(reader_role.oid, relation.oid, 'REFERENCES')
             OR has_table_privilege(reader_role.oid, relation.oid, 'TRIGGER')
             OR has_any_column_privilege(reader_role.oid, relation.oid, 'SELECT')
             OR has_any_column_privilege(reader_role.oid, relation.oid, 'INSERT')
             OR has_any_column_privilege(reader_role.oid, relation.oid, 'UPDATE')
             OR has_any_column_privilege(reader_role.oid, relation.oid, 'REFERENCES')
             OR has_table_privilege(login_role.oid, relation.oid, 'SELECT')
             OR has_table_privilege(login_role.oid, relation.oid, 'INSERT')
             OR has_table_privilege(login_role.oid, relation.oid, 'UPDATE')
             OR has_table_privilege(login_role.oid, relation.oid, 'DELETE')
             OR has_table_privilege(login_role.oid, relation.oid, 'TRUNCATE')
             OR has_table_privilege(login_role.oid, relation.oid, 'REFERENCES')
             OR has_table_privilege(login_role.oid, relation.oid, 'TRIGGER')
             OR has_any_column_privilege(login_role.oid, relation.oid, 'SELECT')
             OR has_any_column_privilege(login_role.oid, relation.oid, 'INSERT')
             OR has_any_column_privilege(login_role.oid, relation.oid, 'UPDATE')
             OR has_any_column_privilege(login_role.oid, relation.oid, 'REFERENCES'))
    ) THEN
        RAISE EXCEPTION 'V175 Workspace scope-reader roles must not access other application tables';
    END IF;
END;
$$;

SELECT tenant_management.apply_v175_workspace_scope_reader_policy();
SELECT tenant_management.apply_v175_workspace_scope_reader_workspace_grants();
SELECT tenant_management.apply_v175_workspace_scope_reader_tenant_policy();
SELECT tenant_management.apply_v175_workspace_scope_reader_tenant_grants();
SELECT tenant_management.apply_v175_workspace_scope_reader_membership_grants();

DO $$
DECLARE
    scan_policy text;
BEGIN
    SELECT qual INTO scan_policy
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'workspace'
       AND policyname = 'v175_workspace_scope_reader_scan'
       AND cmd = 'SELECT';
    IF scan_policy IS NULL
        OR lower(scan_policy) NOT LIKE '%current_user%'
        OR lower(scan_policy) NOT LIKE '%nexa_workspace_scope_reader%'
        OR lower(scan_policy) NOT LIKE '%app.cross_scope_workspace_scan%' THEN
        RAISE EXCEPTION 'V175 Workspace scan policy must require the dedicated role and transaction-local scan GUC';
    END IF;

    SELECT qual INTO scan_policy
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'tenant'
       AND policyname = 'v175_tenant_scope_reader_scan'
       AND cmd = 'SELECT';
    IF scan_policy IS NULL
        OR lower(scan_policy) NOT LIKE '%current_user%'
        OR lower(scan_policy) NOT LIKE '%nexa_workspace_scope_reader%'
        OR lower(scan_policy) NOT LIKE '%app.cross_scope_workspace_scan%' THEN
        RAISE EXCEPTION 'V175 Tenant scan policy must require the dedicated role and transaction-local scan GUC';
    END IF;

    SELECT qual INTO scan_policy
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'workspace'
       AND policyname = 'business_documents_scope_reader_ready_workspace'
       AND permissive = 'RESTRICTIVE'
       AND cmd = 'SELECT';
    IF scan_policy IS NULL
        OR lower(scan_policy) LIKE '%tenant_business_database_binding%'
        OR lower(scan_policy) LIKE '%tenant_business_database_workspace_anchor_task%'
        OR lower(scan_policy) NOT LIKE '%nexa_business_documents_scope_reader%'
        OR lower(scan_policy) NOT LIKE '%app.cross_scope_workspace_scan%' THEN
        RAISE EXCEPTION 'V175 must remove cross-table readiness lookups from the Workspace SELECT policy';
    END IF;

    IF has_table_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace', 'SELECT')
        OR has_table_privilege('nexa_workspace_scope_reader', 'tenant_management.tenant', 'SELECT')
        OR has_table_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace_membership', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace', 'tenant_id', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace', 'id', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace', 'status', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.tenant', 'id', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.tenant', 'status', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace_membership', 'tenant_id', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace_membership', 'id', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace_membership', 'workspace_id', 'SELECT')
        OR NOT has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace_membership', 'user_id', 'SELECT')
        OR has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.workspace', 'slug', 'SELECT')
        OR has_column_privilege('nexa_workspace_scope_reader', 'tenant_management.tenant', 'slug', 'SELECT')
        OR has_table_privilege('nexa_workspace_scope_reader', 'sales.client_account', 'SELECT')
        OR has_table_privilege('nexa_workspace_scope_reader', 'sales.client_account', 'INSERT')
        OR has_table_privilege('nexa_workspace_scope_reader', 'sales.client_account', 'UPDATE')
        OR has_table_privilege('nexa_workspace_scope_reader', 'sales.client_account', 'DELETE')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace', 'SELECT')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace', 'INSERT')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace', 'UPDATE')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace', 'DELETE')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.tenant', 'SELECT')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.tenant', 'INSERT')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.tenant', 'UPDATE')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.tenant', 'DELETE')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace_membership', 'SELECT')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace_membership', 'INSERT')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace_membership', 'UPDATE')
        OR has_table_privilege('nexa_workspace_scope_reader_login', 'tenant_management.workspace_membership', 'DELETE') THEN
        RAISE EXCEPTION 'V175 did not establish the exact Workspace scope-reader privilege boundary';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_attribute attribute
         WHERE attribute.attrelid = 'tenant_management.workspace_membership'::regclass
           AND attribute.attnum > 0 AND NOT attribute.attisdropped
           AND attribute.attname NOT IN ('tenant_id', 'id', 'workspace_id', 'user_id')
           AND has_column_privilege('nexa_workspace_scope_reader', attribute.attrelid, attribute.attnum, 'SELECT')
    ) OR EXISTS (
        SELECT 1
          FROM pg_attribute attribute
         WHERE attribute.attrelid = 'tenant_management.workspace_membership'::regclass
           AND attribute.attnum > 0 AND NOT attribute.attisdropped
           AND (has_column_privilege('nexa_workspace_scope_reader', attribute.attrelid, attribute.attnum, 'INSERT')
             OR has_column_privilege('nexa_workspace_scope_reader', attribute.attrelid, attribute.attnum, 'UPDATE')
             OR has_column_privilege('nexa_workspace_scope_reader', attribute.attrelid, attribute.attnum, 'REFERENCES')
             OR has_column_privilege('nexa_workspace_scope_reader_login', attribute.attrelid, attribute.attnum, 'SELECT')
             OR has_column_privilege('nexa_workspace_scope_reader_login', attribute.attrelid, attribute.attnum, 'INSERT')
             OR has_column_privilege('nexa_workspace_scope_reader_login', attribute.attrelid, attribute.attnum, 'UPDATE')
             OR has_column_privilege('nexa_workspace_scope_reader_login', attribute.attrelid, attribute.attnum, 'REFERENCES'))
    ) THEN
        RAISE EXCEPTION 'V175 did not establish the exact column-only Workspace Membership ACL';
    END IF;

    SELECT qual INTO scan_policy
      FROM pg_policies
     WHERE schemaname = 'tenant_management'
       AND tablename = 'workspace_membership'
       AND policyname = 'v175_workspace_scope_reader_block_membership'
       AND permissive = 'RESTRICTIVE'
       AND cmd = 'SELECT';
    IF scan_policy IS NULL
        OR replace(lower(scan_policy), '"', '')
            NOT LIKE '%current_user <> ''nexa_workspace_scope_reader''%' THEN
        RAISE EXCEPTION 'V175 must deny all direct Workspace Membership rows to the scope reader';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
           AND schema_row.nspname NOT LIKE 'pg_toast%'
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND relation.oid NOT IN ('tenant_management.workspace'::regclass,
                                    'tenant_management.tenant'::regclass,
                                    'tenant_management.workspace_membership'::regclass)
           AND (has_table_privilege('nexa_workspace_scope_reader', relation.oid, 'SELECT')
             OR has_table_privilege('nexa_workspace_scope_reader', relation.oid, 'INSERT')
             OR has_table_privilege('nexa_workspace_scope_reader', relation.oid, 'UPDATE')
             OR has_table_privilege('nexa_workspace_scope_reader', relation.oid, 'DELETE')
             OR has_table_privilege('nexa_workspace_scope_reader', relation.oid, 'TRUNCATE')
             OR has_table_privilege('nexa_workspace_scope_reader', relation.oid, 'REFERENCES')
             OR has_table_privilege('nexa_workspace_scope_reader', relation.oid, 'TRIGGER')
             OR has_any_column_privilege('nexa_workspace_scope_reader', relation.oid, 'SELECT')
             OR has_any_column_privilege('nexa_workspace_scope_reader', relation.oid, 'INSERT')
             OR has_any_column_privilege('nexa_workspace_scope_reader', relation.oid, 'UPDATE')
             OR has_any_column_privilege('nexa_workspace_scope_reader', relation.oid, 'REFERENCES')
             OR has_table_privilege('nexa_workspace_scope_reader_login', relation.oid, 'SELECT')
             OR has_table_privilege('nexa_workspace_scope_reader_login', relation.oid, 'INSERT')
             OR has_table_privilege('nexa_workspace_scope_reader_login', relation.oid, 'UPDATE')
             OR has_table_privilege('nexa_workspace_scope_reader_login', relation.oid, 'DELETE')
             OR has_table_privilege('nexa_workspace_scope_reader_login', relation.oid, 'TRUNCATE')
             OR has_table_privilege('nexa_workspace_scope_reader_login', relation.oid, 'REFERENCES')
             OR has_table_privilege('nexa_workspace_scope_reader_login', relation.oid, 'TRIGGER')
             OR has_any_column_privilege('nexa_workspace_scope_reader_login', relation.oid, 'SELECT')
             OR has_any_column_privilege('nexa_workspace_scope_reader_login', relation.oid, 'INSERT')
             OR has_any_column_privilege('nexa_workspace_scope_reader_login', relation.oid, 'UPDATE')
             OR has_any_column_privilege('nexa_workspace_scope_reader_login', relation.oid, 'REFERENCES'))
    ) THEN
        RAISE EXCEPTION 'V175 did not leave reader roles without access to other application tables';
    END IF;
END;
$$;
