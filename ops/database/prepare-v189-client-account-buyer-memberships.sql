-- Prepare one owner-bound DDL helper for the central Buyer relationship table.
-- The restricted Flyway login receives EXECUTE only; the data model remains
-- under its current shared owner and forced RLS policies.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    membership_oid oid;
    account_oid oid;
    workspace_oid oid;
    membership_owner oid;
    account_owner oid;
    workspace_owner oid;
    apply_helper_oid oid;
    legacy_scan_helper_oid oid;
    cleanup_helper_oid oid;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V189 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 188 THEN
        RAISE EXCEPTION 'V189 preparation requires central Flyway version 188 to be current';
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
    IF runtime_role.oid IS NULL OR NOT runtime_role.rolcanlogin OR runtime_role.rolinherit
        OR runtime_role.rolsuper OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole
        OR runtime_role.rolreplication OR runtime_role.rolbypassrls
        OR EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = runtime_role.oid OR roleid = runtime_role.oid) THEN
        RAISE EXCEPTION 'V189 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.oid, relation.relowner
      INTO membership_oid, membership_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account_membership'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO account_oid, account_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO workspace_oid, workspace_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'workspace'
       AND relation.relkind = 'r';

    IF membership_oid IS NULL OR account_oid IS NULL OR workspace_oid IS NULL OR workspace_owner IS NULL
        OR membership_owner <> account_owner
        OR membership_owner = runtime_role.oid OR pg_has_role(runtime_role.oid, membership_owner, 'MEMBER')
        OR workspace_owner = runtime_role.oid OR pg_has_role(runtime_role.oid, workspace_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V189 requires account, Buyer membership, and workspace tables with a non-runtime owner';
    END IF;

    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> membership_owner
        AND NOT pg_has_role(current_role_oid, membership_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V189 preparation must run as the shared table owner or an authorized database administrator';
    END IF;
    IF NOT current_role_super AND current_role_oid <> workspace_owner
        AND NOT pg_has_role(current_role_oid, workspace_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V189 preparation must be authorized to install and remove the temporary Workspace scan policy';
    END IF;
    IF NOT has_schema_privilege(membership_owner, 'sales', 'USAGE')
        OR NOT has_schema_privilege(membership_owner, 'sales', 'CREATE')
        OR NOT has_schema_privilege(membership_owner, 'tenant_management', 'USAGE')
        OR NOT has_schema_privilege(workspace_owner, 'tenant_management', 'USAGE')
        OR (NOT current_role_super AND NOT has_schema_privilege(current_role_oid, 'tenant_management', 'CREATE'))
        OR (NOT current_role_super AND NOT has_schema_privilege(current_role_oid, 'sales', 'CREATE'))
        OR NOT has_schema_privilege(migration_role.oid, 'sales', 'USAGE')
        OR has_schema_privilege(migration_role.oid, 'sales', 'CREATE') THEN
        RAISE EXCEPTION 'V189 requires sales DDL authority to remain with the shared owner';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = membership_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = account_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = workspace_oid AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'V189 requires forced Tenant/Workspace RLS on all scanned tables';
    END IF;
    IF NOT has_table_privilege(runtime_role.oid, membership_oid, 'SELECT')
        OR NOT has_table_privilege(runtime_role.oid, membership_oid, 'INSERT')
        OR NOT has_table_privilege(runtime_role.oid, membership_oid, 'UPDATE')
        OR has_table_privilege(runtime_role.oid, membership_oid, 'TRUNCATE')
        OR has_table_privilege(runtime_role.oid, membership_oid, 'REFERENCES')
        OR has_table_privilege(runtime_role.oid, membership_oid, 'TRIGGER') THEN
        RAISE EXCEPTION 'V189 requires scoped runtime DML without table-administration privileges';
    END IF;
    IF NOT has_schema_privilege(membership_owner, 'tenant_management', 'USAGE')
        OR NOT has_table_privilege(membership_owner, account_oid, 'SELECT')
        OR NOT has_table_privilege(membership_owner, account_oid, 'UPDATE')
        OR NOT has_table_privilege(membership_owner, account_oid, 'REFERENCES')
        OR NOT has_table_privilege(membership_owner, workspace_oid, 'SELECT')
        OR NOT has_table_privilege(workspace_owner, workspace_oid, 'SELECT') THEN
        RAISE EXCEPTION 'V189 requires scoped Client Account and Workspace access';
    END IF;

    apply_helper_oid := to_regprocedure('sales.apply_v189_client_account_buyer_memberships()');
    legacy_scan_helper_oid := to_regprocedure('tenant_management.v189_client_account_workspace_scopes()');
    cleanup_helper_oid := to_regprocedure('tenant_management.cleanup_v189_client_account_workspace_scan()');
    IF (apply_helper_oid IS NOT NULL AND (
            (SELECT proowner FROM pg_proc WHERE oid = apply_helper_oid) <> membership_owner
            OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = apply_helper_oid)
            OR (SELECT proconfig FROM pg_proc WHERE oid = apply_helper_oid)
                   IS DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]
            OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = apply_helper_oid)
                   <> '0080bafe120b3afd5c76de717b07e109'
            OR NOT has_function_privilege(migration_role.oid, apply_helper_oid, 'EXECUTE')
            OR has_function_privilege(runtime_role.oid, apply_helper_oid, 'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_proc function_row
                        CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                               acldefault('f', function_row.proowner))) acl
                       WHERE function_row.oid = apply_helper_oid
                         AND acl.grantee <> function_row.proowner
                         AND (acl.grantee <> migration_role.oid OR acl.is_grantable))
        ))
        OR legacy_scan_helper_oid IS NOT NULL
        OR (cleanup_helper_oid IS NOT NULL AND (
            (SELECT proowner FROM pg_proc WHERE oid = cleanup_helper_oid) <> workspace_owner
            OR NOT (SELECT prosecdef FROM pg_proc WHERE oid = cleanup_helper_oid)
            OR (SELECT proconfig FROM pg_proc WHERE oid = cleanup_helper_oid)
                   IS DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]
            OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = cleanup_helper_oid)
                   <> 'd1a2eed725a43b0a6d5d1b1eacd0fef4'
            OR NOT has_function_privilege(membership_owner, cleanup_helper_oid, 'EXECUTE')
            OR has_function_privilege(migration_role.oid, cleanup_helper_oid, 'EXECUTE')
            OR has_function_privilege(runtime_role.oid, cleanup_helper_oid, 'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_proc function_row
                        CROSS JOIN LATERAL aclexplode(coalesce(function_row.proacl,
                                                               acldefault('f', function_row.proowner))) acl
                       WHERE function_row.oid = cleanup_helper_oid
                         AND acl.grantee <> function_row.proowner
                         AND (acl.grantee <> membership_owner OR acl.is_grantable))
        ))
        OR to_regprocedure('sales.enforce_client_account_membership_identity_v189()') IS NOT NULL
        OR to_regprocedure('sales.prevent_client_account_type_change_after_buyer_link_v189()') IS NOT NULL
        OR to_regclass('sales.uq_client_account_individual_buyer_v189') IS NOT NULL
        OR EXISTS (
            SELECT 1 FROM pg_attribute
             WHERE attrelid = membership_oid
               AND attname IN ('status', 'updated_at', 'version', 'individual_account_id')
               AND attnum > 0 AND NOT attisdropped
        )
        OR to_regclass('sales.uq_client_account_one_buyer') IS NULL THEN
        RAISE EXCEPTION 'V189 artifacts already exist or the expected legacy Buyer constraint is missing';
    END IF;
END;
$$;

-- This temporary permissive policy lets only the V189 shared-owner helper
-- enumerate Workspace IDs while invoked by the restricted migration login.
-- FORCE RLS remains enabled and runtime/application roles cannot SET ROLE to
-- the shared owner.
DO $$
DECLARE
    buyer_owner_name text;
BEGIN
    SELECT pg_get_userbyid(relation.relowner) INTO buyer_owner_name
      FROM pg_class relation
     WHERE relation.oid = 'sales.client_account_membership'::regclass;
    EXECUTE 'DROP POLICY IF EXISTS v189_client_account_membership_workspace_scan ON tenant_management.workspace';
    EXECUTE format($policy$
        CREATE POLICY v189_client_account_membership_workspace_scan
            ON tenant_management.workspace
            FOR SELECT
            USING (
                current_user = %L
                AND session_user = 'nexa_migrator'
                AND current_setting('app.v189_workspace_scan', true) = 'true'
            )
    $policy$, buyer_owner_name);
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.cleanup_v189_client_account_workspace_scan()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    workspace_owner oid;
BEGIN
    SELECT relation.relowner INTO workspace_owner
      FROM pg_class relation
     WHERE relation.oid = 'tenant_management.workspace'::regclass;
    IF session_user <> 'nexa_migrator'
        OR current_user <> pg_get_userbyid(workspace_owner)
        OR current_setting('app.v189_workspace_scan', true) = 'true' THEN
        RAISE EXCEPTION 'V189 Workspace scan policy cleanup requires the owner-bound migration helper after scope enumeration';
    END IF;
    DROP POLICY IF EXISTS v189_client_account_membership_workspace_scan ON tenant_management.workspace;
END;
$function$;

DO $$
DECLARE
    workspace_owner oid;
    workspace_owner_name text;
    buyer_owner_name text;
    buyer_owner oid;
    cleanup_helper_oid oid;
    function_grantee record;
BEGIN
    SELECT relation.relowner INTO workspace_owner
      FROM pg_class relation
     WHERE relation.oid = 'tenant_management.workspace'::regclass;
    SELECT relation.relowner INTO STRICT buyer_owner
      FROM pg_class relation
     WHERE relation.oid = 'sales.client_account_membership'::regclass;
    workspace_owner_name := pg_get_userbyid(workspace_owner);
    buyer_owner_name := pg_get_userbyid(buyer_owner);
    EXECUTE format('ALTER FUNCTION tenant_management.cleanup_v189_client_account_workspace_scan() OWNER TO %I',
        workspace_owner_name);
    cleanup_helper_oid := 'tenant_management.cleanup_v189_client_account_workspace_scan()'::regprocedure;

    FOR function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = cleanup_helper_oid
           AND acl.grantee <> function_catalog.proowner
    LOOP
        IF function_grantee.grantee = 0 THEN
            EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.cleanup_v189_client_account_workspace_scan() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.cleanup_v189_client_account_workspace_scan() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;
    EXECUTE format('GRANT EXECUTE ON FUNCTION tenant_management.cleanup_v189_client_account_workspace_scan() TO %I',
        buyer_owner_name);

END;
$$;

CREATE OR REPLACE FUNCTION sales.apply_v189_client_account_buyer_memberships()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    membership_oid oid;
    account_oid oid;
    workspace_oid oid;
    membership_owner oid;
    account_owner oid;
    function_grantee record;
    workspace_scope RECORD;
    previous_tenant_scope TEXT;
    previous_workspace_scope TEXT;
    previous_v189_workspace_scan TEXT;
    previous_row_security TEXT;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO membership_oid, membership_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account_membership'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO account_oid, account_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account'
       AND relation.relkind = 'r';
    SELECT relation.oid
      INTO workspace_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'workspace'
       AND relation.relkind = 'r';

    IF session_user <> 'nexa_migrator'
        OR current_user <> pg_get_userbyid(membership_owner)
        OR membership_oid IS NULL OR account_oid IS NULL OR workspace_oid IS NULL
        OR membership_owner <> account_owner
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = membership_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = account_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = workspace_oid AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'V189 DDL may only run in the direct nexa_migrator session under the shared owner with forced RLS intact';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = membership_oid
           AND attname IN ('status', 'updated_at', 'version', 'individual_account_id')
           AND attnum > 0 AND NOT attisdropped
    ) OR to_regclass('sales.uq_client_account_one_buyer') IS NULL
      OR to_regclass('sales.uq_client_account_individual_buyer_v189') IS NOT NULL THEN
        RAISE EXCEPTION 'V189 Buyer membership DDL artifacts are already present';
    END IF;

    ALTER TABLE sales.client_account_membership
        ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
        ADD COLUMN updated_at TIMESTAMPTZ,
        ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
        ADD COLUMN individual_account_id UUID;

    previous_tenant_scope := current_setting('app.current_tenant_id', true);
    previous_workspace_scope := current_setting('app.current_workspace_id', true);
    previous_v189_workspace_scan := current_setting('app.v189_workspace_scan', true);
    previous_row_security := current_setting('row_security');
    PERFORM set_config('row_security', 'on', true);
    PERFORM set_config('app.v189_workspace_scan', 'true', true);

    FOR workspace_scope IN
        SELECT workspace.tenant_id, workspace.id AS workspace_id
          FROM tenant_management.workspace workspace
         ORDER BY workspace.tenant_id, workspace.id
    LOOP
        PERFORM set_config('app.current_tenant_id', workspace_scope.tenant_id::TEXT, true);
        PERFORM set_config('app.current_workspace_id', workspace_scope.workspace_id::TEXT, true);

        UPDATE sales.client_account_membership member
           SET updated_at = member.created_at,
               individual_account_id = CASE
                   WHEN account.account_type = 'INDIVIDUAL' THEN member.client_account_id
               END
          FROM sales.client_account account
         WHERE account.tenant_id = member.tenant_id
           AND account.workspace_id = member.workspace_id
           AND account.id = member.client_account_id
           AND member.tenant_id = workspace_scope.tenant_id
           AND member.workspace_id = workspace_scope.workspace_id;
    END LOOP;

    PERFORM set_config('app.current_tenant_id', COALESCE(previous_tenant_scope, ''), true);
    PERFORM set_config('app.current_workspace_id', COALESCE(previous_workspace_scope, ''), true);
    PERFORM set_config('app.v189_workspace_scan', '', true);
    PERFORM set_config('row_security', previous_row_security, true);
    PERFORM tenant_management.cleanup_v189_client_account_workspace_scan();
    PERFORM set_config('app.v189_workspace_scan', COALESCE(previous_v189_workspace_scan, ''), true);

    ALTER TABLE sales.client_account_membership
        ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
        ALTER COLUMN updated_at SET NOT NULL,
        ADD CONSTRAINT ck_client_account_membership_status_v189
            CHECK (status IN ('ACTIVE', 'INACTIVE')),
        ADD CONSTRAINT ck_client_account_membership_version_v189
            CHECK (version >= 0),
        ADD CONSTRAINT ck_client_account_membership_individual_account_v189
            CHECK (individual_account_id IS NULL OR individual_account_id = client_account_id),
        ADD CONSTRAINT fk_client_account_membership_individual_account_v189
            FOREIGN KEY (tenant_id, workspace_id, individual_account_id)
            REFERENCES sales.client_account (tenant_id, workspace_id, id);

    DROP INDEX sales.uq_client_account_one_buyer;
    CREATE UNIQUE INDEX uq_client_account_individual_buyer_v189
        ON sales.client_account_membership (tenant_id, workspace_id, individual_account_id)
        WHERE individual_account_id IS NOT NULL;

    CREATE OR REPLACE FUNCTION sales.enforce_client_account_membership_identity_v189()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
    AS $trigger$
    DECLARE
        linked_account_type VARCHAR(16);
    BEGIN
        IF TG_OP = 'UPDATE' AND ROW(
                OLD.client_account_id, OLD.workspace_membership_id, OLD.tenant_id, OLD.workspace_id, OLD.created_at
            ) IS DISTINCT FROM ROW(
                NEW.client_account_id, NEW.workspace_membership_id, NEW.tenant_id, NEW.workspace_id, NEW.created_at
            ) THEN
            RAISE EXCEPTION 'Buyer account membership identity is immutable'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_client_account_membership_identity_immutable';
        END IF;

        SELECT account.account_type
          INTO linked_account_type
          FROM sales.client_account account
         WHERE account.tenant_id = NEW.tenant_id
           AND account.workspace_id = NEW.workspace_id
           AND account.id = NEW.client_account_id
         FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Client Account for Buyer membership is not visible in the active Tenant and Workspace'
                USING ERRCODE = '23503', CONSTRAINT = 'fk_client_membership_scope_account';
        END IF;

        IF linked_account_type = 'INDIVIDUAL' THEN
            IF EXISTS (
                SELECT 1
                  FROM sales.client_account_membership member
                 WHERE member.tenant_id = NEW.tenant_id
                   AND member.workspace_id = NEW.workspace_id
                   AND member.client_account_id = NEW.client_account_id
                   AND member.workspace_membership_id IS DISTINCT FROM NEW.workspace_membership_id
            ) THEN
                RAISE EXCEPTION 'An individual Client Account can have only one Buyer membership'
                    USING ERRCODE = '23505', CONSTRAINT = 'uq_client_account_individual_buyer_v189';
            END IF;
            NEW.individual_account_id := NEW.client_account_id;
        ELSE
            NEW.individual_account_id := NULL;
        END IF;
        RETURN NEW;
    END;
    $trigger$;

    CREATE TRIGGER client_account_membership_identity_guard_v189
        BEFORE INSERT OR UPDATE ON sales.client_account_membership
        FOR EACH ROW EXECUTE FUNCTION sales.enforce_client_account_membership_identity_v189();

    CREATE OR REPLACE FUNCTION sales.prevent_client_account_type_change_after_buyer_link_v189()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
    AS $trigger$
    BEGIN
        IF NEW.account_type IS DISTINCT FROM OLD.account_type
           AND EXISTS (
               SELECT 1
                 FROM sales.client_account_membership member
                WHERE member.tenant_id = OLD.tenant_id
                  AND member.workspace_id = OLD.workspace_id
                  AND member.client_account_id = OLD.id
           ) THEN
            RAISE EXCEPTION 'Client Account type cannot change after Buyer membership is linked'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_client_account_type_immutable_after_buyer_link';
        END IF;
        RETURN NEW;
    END;
    $trigger$;

    CREATE TRIGGER client_account_type_membership_guard_v189
        BEFORE UPDATE OF account_type ON sales.client_account
        FOR EACH ROW EXECUTE FUNCTION sales.prevent_client_account_type_change_after_buyer_link_v189();

    COMMENT ON COLUMN sales.client_account_membership.status IS
        'Current Buyer-to-account access state. INACTIVE preserves the historical relationship row and dependent Buyer data.';
    COMMENT ON COLUMN sales.client_account_membership.individual_account_id IS
        'Database-enforced cardinality key set only for INDIVIDUAL Client Accounts; one personal Buyer membership per account.';

    REVOKE DELETE ON TABLE sales.client_account_membership FROM nexa_runtime;
    REVOKE ALL PRIVILEGES ON FUNCTION sales.enforce_client_account_membership_identity_v189() FROM PUBLIC;
    REVOKE ALL PRIVILEGES ON FUNCTION sales.prevent_client_account_type_change_after_buyer_link_v189() FROM PUBLIC;

    FOR function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = 'sales.apply_v189_client_account_buyer_memberships()'::regprocedure
           AND acl.grantee <> function_catalog.proowner
    LOOP
        IF function_grantee.grantee = 0 THEN
            EXECUTE 'REVOKE ALL ON FUNCTION sales.apply_v189_client_account_buyer_memberships() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION sales.apply_v189_client_account_buyer_memberships() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    EXECUTE 'REVOKE ALL ON FUNCTION sales.apply_v189_client_account_buyer_memberships() FROM nexa_migrator';
END;
$function$;

DO $$
DECLARE
    table_owner oid;
    helper_oid oid;
    function_grantee record;
BEGIN
    SELECT relation.relowner INTO table_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account_membership'
       AND relation.relkind = 'r';
    helper_oid := 'sales.apply_v189_client_account_buyer_memberships()'::regprocedure;

    EXECUTE format('ALTER FUNCTION sales.apply_v189_client_account_buyer_memberships() OWNER TO %I',
        pg_get_userbyid(table_owner));

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
            EXECUTE 'REVOKE ALL ON FUNCTION sales.apply_v189_client_account_buyer_memberships() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION sales.apply_v189_client_account_buyer_memberships() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION sales.apply_v189_client_account_buyer_memberships() TO nexa_migrator;
END;
$$;

COMMIT;
