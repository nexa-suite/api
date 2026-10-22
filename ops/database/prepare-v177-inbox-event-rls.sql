-- Install the owner-bound helper before restricted Flyway applies V177.
-- nexa_migrator does not own integration.inbox_event and must not receive
-- direct DDL privileges on the integration schema.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    inbox_owner oid;
    inbox_owner_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner') THEN
        RAISE EXCEPTION 'V177 preparation must run outside runtime, migrator, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) NOT IN (174, 175, 176) THEN
        RAISE EXCEPTION 'V177 preparation requires central Flyway version 174, 175, or 176 to be current';
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
        RAISE EXCEPTION 'V177 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.relowner INTO inbox_owner
      FROM pg_class relation
      JOIN pg_namespace ns ON ns.oid = relation.relnamespace
     WHERE ns.nspname = 'integration'
       AND relation.relname = 'inbox_event'
       AND relation.relkind = 'r';
    IF inbox_owner IS NULL THEN
        RAISE EXCEPTION 'V177 requires integration.inbox_event';
    END IF;
    SELECT rolsuper INTO inbox_owner_super FROM pg_roles WHERE oid = inbox_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> inbox_owner
        AND NOT pg_has_role(current_role_oid, inbox_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V177 preparation must run as the inbox table owner or an authorized member';
    END IF;
    IF NOT inbox_owner_super
        AND (NOT has_schema_privilege(inbox_owner, 'tenant_management', 'USAGE')
            OR NOT has_schema_privilege(inbox_owner, 'tenant_management', 'CREATE')
            OR NOT has_schema_privilege(inbox_owner, 'integration', 'USAGE')
            OR NOT has_table_privilege(inbox_owner, 'integration.outbox_event', 'SELECT')) THEN
        RAISE EXCEPTION 'The inbox owner must access the integration event tables and own the V177 helper in tenant_management';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v177_inbox_event_rls()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    inbox_owner oid;
    relation_oid oid;
    inbox_column name;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO relation_oid, inbox_owner
      FROM pg_class relation
      JOIN pg_namespace ns ON ns.oid = relation.relnamespace
     WHERE ns.nspname = 'integration'
       AND relation.relname = 'inbox_event'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR relation_oid IS NULL
        OR current_user <> pg_get_userbyid(inbox_owner) THEN
        RAISE EXCEPTION 'V177 inbox RLS may only be applied by nexa_migrator under the table owner';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM integration.inbox_event receipt
          JOIN integration.outbox_event event ON event.event_id = receipt.event_id
         WHERE receipt.tenant_id IS DISTINCT FROM event.tenant_id
            OR receipt.workspace_id IS DISTINCT FROM event.workspace_id
    ) OR EXISTS (
        SELECT 1
          FROM integration.inbox_event first_receipt
          JOIN integration.inbox_event second_receipt
            ON second_receipt.event_id = first_receipt.event_id
           AND second_receipt.consumer_name > first_receipt.consumer_name
         WHERE first_receipt.tenant_id IS DISTINCT FROM second_receipt.tenant_id
            OR first_receipt.workspace_id IS DISTINCT FROM second_receipt.workspace_id
    ) THEN
        RAISE EXCEPTION 'Central V177 found inbox receipts whose Tenant/Workspace scope differs from their source event';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_policy p
         WHERE p.polrelid = relation_oid
           AND p.polname NOT IN (
               'inbox_event_direct_scope_select',
               'inbox_event_direct_scope_insert'
           )
    ) THEN
        RAISE EXCEPTION 'V177 found an unexpected inbox policy that could widen access';
    END IF;

    EXECUTE 'ALTER TABLE integration.inbox_event ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE integration.inbox_event FORCE ROW LEVEL SECURITY';
    EXECUTE 'DROP POLICY IF EXISTS inbox_event_direct_scope_select ON integration.inbox_event';
    EXECUTE $policy$
        CREATE POLICY inbox_event_direct_scope_select ON integration.inbox_event
        FOR SELECT USING (
            tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        )
    $policy$;
    EXECUTE 'DROP POLICY IF EXISTS inbox_event_direct_scope_insert ON integration.inbox_event';
    EXECUTE $policy$
        CREATE POLICY inbox_event_direct_scope_insert ON integration.inbox_event
        FOR INSERT WITH CHECK (
            tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        )
    $policy$;

    EXECUTE 'REVOKE ALL PRIVILEGES ON TABLE integration.inbox_event FROM PUBLIC, nexa_runtime';
    FOR inbox_column IN
        SELECT attname
          FROM pg_attribute
         WHERE attrelid = relation_oid
           AND attnum > 0
           AND NOT attisdropped
    LOOP
        -- Column privileges survive a table-level REVOKE, so remove them too.
        EXECUTE format('REVOKE ALL PRIVILEGES (%I) ON TABLE integration.inbox_event FROM PUBLIC, nexa_runtime',
            inbox_column);
    END LOOP;
    EXECUTE 'GRANT SELECT, INSERT ON TABLE integration.inbox_event TO nexa_runtime';
    EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_management.apply_v177_inbox_event_rls() FROM nexa_migrator';

    IF NOT EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace ns ON ns.oid = relation.relnamespace
         WHERE relation.oid = relation_oid
           AND ns.nspname = 'integration'
           AND relation.relname = 'inbox_event'
           AND relation.relrowsecurity
           AND relation.relforcerowsecurity
    ) OR NOT has_table_privilege('nexa_runtime', 'integration.inbox_event', 'SELECT')
        OR NOT has_table_privilege('nexa_runtime', 'integration.inbox_event', 'INSERT')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'UPDATE')
        OR has_any_column_privilege('nexa_runtime', 'integration.inbox_event', 'UPDATE')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'DELETE')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'TRUNCATE')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'REFERENCES')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'TRIGGER') THEN
        RAISE EXCEPTION 'V177 failed to force scope RLS and limit runtime inbox access to SELECT/INSERT';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_policy p
         WHERE p.polrelid = relation_oid
           AND p.polname = 'inbox_event_direct_scope_select'
           AND p.polcmd = 'r'
           AND pg_get_expr(p.polqual, p.polrelid) LIKE '%app.current_tenant_id%'
           AND pg_get_expr(p.polqual, p.polrelid) LIKE '%app.current_workspace_id%'
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_policy p
         WHERE p.polrelid = relation_oid
           AND p.polname = 'inbox_event_direct_scope_insert'
           AND p.polcmd = 'a'
           AND pg_get_expr(p.polwithcheck, p.polrelid) LIKE '%app.current_tenant_id%'
           AND pg_get_expr(p.polwithcheck, p.polrelid) LIKE '%app.current_workspace_id%'
    ) THEN
        RAISE EXCEPTION 'V177 failed to establish both Tenant/Workspace inbox policies';
    END IF;

    IF has_function_privilege('nexa_migrator',
            'tenant_management.apply_v177_inbox_event_rls()', 'EXECUTE')
        OR has_function_privilege('nexa_runtime',
            'tenant_management.apply_v177_inbox_event_rls()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V177 one-shot inbox RLS helper remains executable';
    END IF;
END;
$function$;

DO $$
DECLARE
    inbox_owner oid;
BEGIN
    SELECT relation.relowner INTO inbox_owner
      FROM pg_class relation
      JOIN pg_namespace ns ON ns.oid = relation.relnamespace
     WHERE ns.nspname = 'integration'
       AND relation.relname = 'inbox_event'
       AND relation.relkind = 'r';
    EXECUTE format('ALTER FUNCTION tenant_management.apply_v177_inbox_event_rls() OWNER TO %I',
        pg_get_userbyid(inbox_owner));
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
     WHERE oid = 'tenant_management.apply_v177_inbox_event_rls()'::regprocedure;

    -- Normalize all non-owner grants before giving the direct migrator its
    -- single-use execution right; CREATE OR REPLACE preserves old ACLs.
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
            REVOKE ALL ON FUNCTION tenant_management.apply_v177_inbox_event_rls() FROM PUBLIC;
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v177_inbox_event_rls() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    REVOKE ALL ON FUNCTION tenant_management.apply_v177_inbox_event_rls() FROM PUBLIC;
    GRANT EXECUTE ON FUNCTION tenant_management.apply_v177_inbox_event_rls() TO nexa_migrator;
    REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION tenant_management.apply_v177_inbox_event_rls()
        FROM nexa_migrator;
END;
$$;

COMMIT;
