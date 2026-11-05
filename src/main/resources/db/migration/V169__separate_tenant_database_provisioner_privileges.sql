-- Keep API tenant onboarding writes atomic while separating queue mutation
-- from the application runtime and the local provisioning worker.
DO $$
DECLARE
    migration_role_oid oid;
BEGIN
    SELECT oid INTO migration_role_oid FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF NOT FOUND OR current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V169 must run in a direct nexa_migrator session';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE ns.nspname = 'tenant_management'
           AND c.relname IN (
               'tenant_business_database_binding',
               'tenant_business_database_provisioning_task',
               'tenant_business_database_workspace_anchor_task'
           )
           AND c.relowner <> migration_role_oid
    ) OR (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE ns.nspname = 'tenant_management'
           AND c.relname IN (
               'tenant_business_database_binding',
               'tenant_business_database_provisioning_task',
               'tenant_business_database_workspace_anchor_task'
           )
    ) <> 3 THEN
        RAISE EXCEPTION 'V169 requires nexa_migrator to own the Tenant binding and provisioning queue tables';
    END IF;
END;
$$;

DO $$
DECLARE
    runtime_role pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO runtime_role FROM pg_roles WHERE rolname = 'nexa_runtime';
    IF NOT FOUND OR NOT runtime_role.rolcanlogin OR runtime_role.rolinherit
        OR runtime_role.rolsuper OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole
        OR runtime_role.rolreplication OR runtime_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = runtime_role.oid OR roleid = runtime_role.oid) THEN
        RAISE EXCEPTION 'Tenant database runtime role must be a restricted NOINHERIT login with no role memberships';
    END IF;
END;
$$;

DROP POLICY tenant_business_database_provisioning_task_scope
    ON tenant_management.tenant_business_database_provisioning_task;
CREATE POLICY tenant_business_database_provisioning_task_select_scope
    ON tenant_management.tenant_business_database_provisioning_task
    FOR SELECT
    USING (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
        OR current_user = 'nexa_tenant_database_provisioner'
    );
CREATE POLICY tenant_business_database_provisioning_task_insert_scope
    ON tenant_management.tenant_business_database_provisioning_task
    FOR INSERT
    WITH CHECK (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    );
CREATE POLICY tenant_business_database_provisioning_task_worker_update
    ON tenant_management.tenant_business_database_provisioning_task
    FOR UPDATE
    USING (current_user = 'nexa_tenant_database_provisioner')
    WITH CHECK (current_user = 'nexa_tenant_database_provisioner');

DROP POLICY tenant_business_database_workspace_anchor_task_scope
    ON tenant_management.tenant_business_database_workspace_anchor_task;
CREATE POLICY tenant_business_database_workspace_anchor_task_select_scope
    ON tenant_management.tenant_business_database_workspace_anchor_task
    FOR SELECT
    USING (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
        OR current_user = 'nexa_tenant_database_provisioner'
    );
CREATE POLICY tenant_business_database_workspace_anchor_task_insert_scope
    ON tenant_management.tenant_business_database_workspace_anchor_task
    FOR INSERT
    WITH CHECK (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    );
CREATE POLICY tenant_business_database_workspace_anchor_task_worker_update
    ON tenant_management.tenant_business_database_workspace_anchor_task
    FOR UPDATE
    USING (current_user = 'nexa_tenant_database_provisioner')
    WITH CHECK (current_user = 'nexa_tenant_database_provisioner');

-- The worker must prove an exact Tenant/Workspace pair without direct access
-- to the identity tables. The security-definer owner needs the narrow column
-- grants documented by ops/database/prepare-v169-tenant-provisioner.sql
-- to evaluate Workspace's existing RLS policies.
CREATE FUNCTION tenant_management.provisioner_scope_exists(p_tenant_id uuid, p_workspace_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
    SELECT p_tenant_id IS NOT NULL
       AND p_workspace_id IS NOT NULL
       AND p_tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
       AND p_workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
       AND EXISTS (
           SELECT 1
             FROM tenant_management.workspace workspace
            WHERE workspace.tenant_id = p_tenant_id
              AND workspace.id = p_workspace_id
       );
$function$;
REVOKE ALL ON FUNCTION tenant_management.provisioner_scope_exists(uuid, uuid) FROM PUBLIC;

-- Flyway creates the helper as nexa_migrator. Keep its existing FORCE RLS
-- behavior and do not transfer ownership through a privileged connection.

DO $$
DECLARE
    migration_role pg_roles%ROWTYPE;
    helper_owner name;
BEGIN
    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF NOT FOUND OR NOT migration_role.rolcanlogin OR migration_role.rolinherit
        OR migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
        OR migration_role.rolreplication OR migration_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = migration_role.oid)
        OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = migration_role.oid)
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
        RAISE EXCEPTION 'Tenant database migration role is missing or has unsafe login, inheritance, or privilege flags';
    END IF;

    SELECT pg_get_userbyid(proowner) INTO helper_owner
      FROM pg_proc
     WHERE oid = 'tenant_management.provisioner_scope_exists(uuid, uuid)'::regprocedure;
    IF helper_owner <> 'nexa_migrator'
        OR NOT has_schema_privilege('nexa_migrator', 'tenant_management', 'USAGE')
        OR NOT has_schema_privilege('nexa_migrator', 'tenant_management', 'CREATE')
        OR has_schema_privilege('nexa_migrator', 'public', 'CREATE')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'tenant_id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'slug', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'status', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'workspace_id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'user_id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.tenant_business_database_binding', 'tenant_id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.tenant_business_database_binding', 'lifecycle_state', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.tenant_business_database_workspace_anchor_task', 'tenant_id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.tenant_business_database_workspace_anchor_task', 'workspace_id', 'SELECT')
        OR NOT has_column_privilege('nexa_migrator', 'tenant_management.tenant_business_database_workspace_anchor_task', 'status', 'SELECT') THEN
        RAISE EXCEPTION 'Tenant database migration role cannot safely evaluate Workspace row security; run the V169 pregrant as an authorized database provisioner';
    END IF;

    IF has_table_privilege('nexa_migrator', 'tenant_management.workspace', 'SELECT')
        OR has_table_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'SELECT')
        OR EXISTS (
            SELECT 1
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace ns ON ns.oid = c.relnamespace
             WHERE ns.nspname = 'tenant_management'
               AND c.relname = 'workspace'
               AND a.attnum > 0
               AND NOT a.attisdropped
               AND a.attname NOT IN ('tenant_id', 'id', 'slug', 'status')
               AND has_column_privilege('nexa_migrator', c.oid, a.attnum, 'SELECT')
        ) OR EXISTS (
            SELECT 1
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace ns ON ns.oid = c.relnamespace
             WHERE ns.nspname = 'tenant_management'
               AND c.relname = 'workspace_membership'
               AND a.attnum > 0
               AND NOT a.attisdropped
               AND a.attname NOT IN ('id', 'workspace_id', 'user_id')
               AND has_column_privilege('nexa_migrator', c.oid, a.attnum, 'SELECT')
        ) THEN
        RAISE EXCEPTION 'Tenant database migration role has broader effective Workspace or Membership reads than the V169 scope helper requires';
    END IF;
END;
$$;

DO $$
DECLARE
    provisioner pg_roles%ROWTYPE;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        REVOKE UPDATE ON tenant_management.tenant_business_database_provisioning_task,
            tenant_management.tenant_business_database_workspace_anchor_task FROM nexa_runtime;
        REVOKE UPDATE (status, attempt_count, database_port, claim_token, lease_until, next_attempt_at,
            failure_code, completed_at, updated_at, version)
            ON tenant_management.tenant_business_database_provisioning_task FROM nexa_runtime;
        REVOKE UPDATE (status, attempt_count, claim_token, lease_until, next_attempt_at,
            failure_code, completed_at, updated_at, version)
            ON tenant_management.tenant_business_database_workspace_anchor_task FROM nexa_runtime;

        IF has_table_privilege('nexa_runtime',
                'tenant_management.tenant_business_database_provisioning_task', 'UPDATE')
            OR has_column_privilege('nexa_runtime',
                'tenant_management.tenant_business_database_provisioning_task', 'status', 'UPDATE')
            OR has_table_privilege('nexa_runtime',
                'tenant_management.tenant_business_database_workspace_anchor_task', 'UPDATE')
            OR has_column_privilege('nexa_runtime',
                'tenant_management.tenant_business_database_workspace_anchor_task', 'status', 'UPDATE') THEN
            RAISE EXCEPTION 'V169 cannot remove the runtime role effective UPDATE access to Tenant provisioning queues';
        END IF;
    END IF;

    SELECT * INTO provisioner FROM pg_roles WHERE rolname = 'nexa_tenant_database_provisioner';
    IF FOUND THEN
        IF NOT provisioner.rolcanlogin OR provisioner.rolsuper OR provisioner.rolcreatedb
            OR provisioner.rolcreaterole OR provisioner.rolreplication OR provisioner.rolbypassrls
            OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = provisioner.oid OR roleid = provisioner.oid)
            OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = provisioner.oid)
            OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = provisioner.oid)
            OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = provisioner.oid)
            OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = provisioner.oid)
            OR EXISTS (
                SELECT 1
                  FROM pg_namespace
                 WHERE nspname NOT LIKE 'pg_temp_%'
                   AND nspname NOT LIKE 'pg_toast_temp_%'
                   AND has_schema_privilege(provisioner.oid, oid, 'CREATE')
            )
            OR has_database_privilege(provisioner.oid, current_database(), 'CREATE') THEN
            RAISE EXCEPTION 'Existing tenant database provisioner role has unsafe privileges'
                USING DETAIL = format(
                    'login=%s superuser=%s createdb=%s createrole=%s replication=%s bypassrls=%s membership=%s database_owner=%s schema_owner=%s object_owner=%s function_owner=%s schema_create=%s database_create=%s',
                    provisioner.rolcanlogin, provisioner.rolsuper, provisioner.rolcreatedb,
                    provisioner.rolcreaterole, provisioner.rolreplication, provisioner.rolbypassrls,
                    EXISTS (SELECT 1 FROM pg_auth_members WHERE member = provisioner.oid OR roleid = provisioner.oid),
                    EXISTS (SELECT 1 FROM pg_database WHERE datdba = provisioner.oid),
                    EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = provisioner.oid),
                    EXISTS (SELECT 1 FROM pg_class WHERE relowner = provisioner.oid),
                    EXISTS (SELECT 1 FROM pg_proc WHERE proowner = provisioner.oid),
                    EXISTS (
                        SELECT 1
                          FROM pg_namespace
                         WHERE nspname NOT LIKE 'pg_temp_%'
                           AND nspname NOT LIKE 'pg_toast_temp_%'
                           AND has_schema_privilege(provisioner.oid, oid, 'CREATE')
                    ),
                    has_database_privilege(provisioner.oid, current_database(), 'CREATE'));
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM pg_database d
              CROSS JOIN LATERAL aclexplode(coalesce(d.datacl,
                  acldefault('d', d.datdba))) privilege
             WHERE d.datname = current_database()
               AND privilege.grantee = provisioner.oid
               AND privilege.privilege_type = 'CONNECT'
        ) OR NOT EXISTS (
            SELECT 1
              FROM pg_namespace ns
              CROSS JOIN LATERAL aclexplode(coalesce(ns.nspacl,
                  acldefault('n', ns.nspowner))) privilege
             WHERE ns.nspname = 'tenant_management'
               AND privilege.grantee = provisioner.oid
               AND privilege.privilege_type = 'USAGE'
        ) OR EXISTS (
            SELECT 1
              FROM pg_database d
              CROSS JOIN LATERAL aclexplode(coalesce(d.datacl,
                  acldefault('d', d.datdba))) privilege
             WHERE privilege.grantee = provisioner.oid
               AND NOT (d.datname = current_database() AND privilege.privilege_type = 'CONNECT')
        ) OR EXISTS (
            SELECT 1
              FROM pg_namespace ns
              CROSS JOIN LATERAL aclexplode(coalesce(ns.nspacl,
                  acldefault('n', ns.nspowner))) privilege
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND privilege.grantee = provisioner.oid
               AND NOT (ns.nspname = 'tenant_management' AND privilege.privilege_type = 'USAGE')
        ) OR EXISTS (
            SELECT 1
              FROM pg_class c
              JOIN pg_namespace ns ON ns.oid = c.relnamespace
              CROSS JOIN LATERAL aclexplode(c.relacl) privilege
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND privilege.grantee = provisioner.oid
        ) OR EXISTS (
            SELECT 1
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace ns ON ns.oid = c.relnamespace
              CROSS JOIN LATERAL aclexplode(a.attacl) privilege
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND NOT a.attisdropped
               AND privilege.grantee = provisioner.oid
        ) OR EXISTS (
            SELECT 1
              FROM pg_proc p
              JOIN pg_namespace ns ON ns.oid = p.pronamespace
              CROSS JOIN LATERAL aclexplode(p.proacl) privilege
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND privilege.grantee = provisioner.oid
        ) OR EXISTS (
            SELECT 1
              FROM pg_default_acl default_acl
              CROSS JOIN LATERAL aclexplode(default_acl.defaclacl) privilege
             WHERE privilege.grantee = provisioner.oid
        ) THEN
            RAISE EXCEPTION 'Tenant database provisioner retains direct grants; run the V169 preparation script before Flyway';
        END IF;

        IF EXISTS (
            SELECT 1
              FROM pg_class c
              JOIN pg_namespace ns ON ns.oid = c.relnamespace
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
               AND c.oid NOT IN (
                   'tenant_management.tenant_business_database_provisioning_task'::regclass,
                   'tenant_management.tenant_business_database_workspace_anchor_task'::regclass,
                   'tenant_management.tenant_business_database_binding'::regclass
               )
               AND (
                   has_table_privilege(provisioner.oid, c.oid, 'SELECT')
                   OR has_table_privilege(provisioner.oid, c.oid, 'INSERT')
                   OR has_table_privilege(provisioner.oid, c.oid, 'UPDATE')
                   OR has_table_privilege(provisioner.oid, c.oid, 'DELETE')
                   OR has_table_privilege(provisioner.oid, c.oid, 'TRUNCATE')
                   OR has_table_privilege(provisioner.oid, c.oid, 'REFERENCES')
                   OR has_table_privilege(provisioner.oid, c.oid, 'TRIGGER')
                   OR EXISTS (
                       SELECT 1
                         FROM pg_attribute a
                        WHERE a.attrelid = c.oid
                          AND a.attnum > 0
                          AND NOT a.attisdropped
                          AND (
                              has_column_privilege(provisioner.oid, c.oid, a.attnum, 'SELECT')
                              OR has_column_privilege(provisioner.oid, c.oid, a.attnum, 'INSERT')
                              OR has_column_privilege(provisioner.oid, c.oid, a.attnum, 'UPDATE')
                              OR has_column_privilege(provisioner.oid, c.oid, a.attnum, 'REFERENCES')
                          )
                   )
               )
        ) OR EXISTS (
            SELECT 1
              FROM pg_class c
              JOIN pg_namespace ns ON ns.oid = c.relnamespace
              CROSS JOIN LATERAL aclexplode(c.relacl) privilege
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
               AND privilege.grantee = 0
        ) OR EXISTS (
            SELECT 1
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace ns ON ns.oid = c.relnamespace
              CROSS JOIN LATERAL aclexplode(a.attacl) privilege
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND NOT a.attisdropped
               AND privilege.grantee = 0
        ) OR EXISTS (
            SELECT 1
              FROM pg_proc p
              JOIN pg_namespace ns ON ns.oid = p.pronamespace
             WHERE ns.nspname <> 'information_schema'
               AND ns.nspname NOT LIKE 'pg_%'
               AND p.prosecdef
               AND p.oid <> 'tenant_management.provisioner_scope_exists(uuid, uuid)'::regprocedure
               AND has_function_privilege(provisioner.oid, p.oid, 'EXECUTE')
        ) THEN
            RAISE EXCEPTION 'Tenant database provisioner has effective access through PUBLIC or other ACLs beyond its queue and binding grants';
        END IF;

        -- Broad worker grants and its database/schema entry points are
        -- normalized by the authorized pre-migration preparation script.
        -- Flyway makes only narrow, object-specific grants here.
        GRANT EXECUTE ON FUNCTION tenant_management.provisioner_scope_exists(uuid, uuid)
            TO nexa_tenant_database_provisioner;
        GRANT SELECT ON tenant_management.tenant_business_database_provisioning_task,
            tenant_management.tenant_business_database_workspace_anchor_task
            TO nexa_tenant_database_provisioner;
        GRANT UPDATE (status, attempt_count, database_port, claim_token, lease_until, next_attempt_at,
            failure_code, completed_at, updated_at, version)
            ON tenant_management.tenant_business_database_provisioning_task TO nexa_tenant_database_provisioner;
        GRANT UPDATE (status, attempt_count, claim_token, lease_until, next_attempt_at,
            failure_code, completed_at, updated_at, version)
            ON tenant_management.tenant_business_database_workspace_anchor_task TO nexa_tenant_database_provisioner;
        GRANT SELECT (tenant_id, database_identity, credential_secret_reference, lifecycle_state,
            created_at, updated_at, version, verified_schema_manifest_sha256,
            wallet_recharge_callback_credential_secret_reference,
            payment_callback_credential_secret_reference)
            ON tenant_management.tenant_business_database_binding TO nexa_tenant_database_provisioner;
        GRANT INSERT (tenant_id, database_identity, credential_secret_reference, lifecycle_state,
            wallet_recharge_callback_credential_secret_reference,
            payment_callback_credential_secret_reference)
            ON tenant_management.tenant_business_database_binding TO nexa_tenant_database_provisioner;
        GRANT UPDATE (lifecycle_state, updated_at, version, verified_schema_manifest_sha256,
            wallet_recharge_callback_credential_secret_reference,
            payment_callback_credential_secret_reference)
            ON tenant_management.tenant_business_database_binding TO nexa_tenant_database_provisioner;
    END IF;
END;
$$;
