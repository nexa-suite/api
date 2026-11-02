-- Install one fixed DDL helper under the existing common owner of the two
-- logistics tables. The restricted Flyway login receives EXECUTE only.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    active_attempt_oid oid;
    handoff_token_oid oid;
    terminal_attempt_oid oid;
    active_attempt_owner oid;
    handoff_token_owner oid;
    owner_super boolean;
    old_trigger_oid oid;
    active_index_oid oid;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V188 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 187 THEN
        RAISE EXCEPTION 'V188 preparation requires central Flyway version 187 to be current';
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
        RAISE EXCEPTION 'V188 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.oid, relation.relowner
      INTO active_attempt_oid, active_attempt_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_active_attempt'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO handoff_token_oid, handoff_token_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_handoff_token'
       AND relation.relkind = 'r';
    SELECT relation.oid
      INTO terminal_attempt_oid
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_attempt'
       AND relation.relkind = 'r';

    IF active_attempt_oid IS NULL OR handoff_token_oid IS NULL OR terminal_attempt_oid IS NULL
        OR active_attempt_owner <> handoff_token_owner
        OR active_attempt_owner = runtime_role.oid
        OR pg_has_role(runtime_role.oid, active_attempt_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V188 requires delivery_active_attempt and delivery_handoff_token to share a non-runtime owner';
    END IF;

    SELECT rolsuper INTO owner_super FROM pg_roles WHERE oid = active_attempt_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> active_attempt_owner
        AND NOT pg_has_role(current_role_oid, active_attempt_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V188 preparation must run as the shared table owner or an authorized database administrator';
    END IF;
    IF NOT owner_super
        AND (NOT has_schema_privilege(active_attempt_owner, 'logistics', 'USAGE')
            OR NOT has_schema_privilege(active_attempt_owner, 'logistics', 'CREATE')
            OR NOT has_table_privilege(active_attempt_owner, terminal_attempt_oid, 'REFERENCES')) THEN
        RAISE EXCEPTION 'The shared table owner must own the helper and reference terminal delivery attempts';
    END IF;
    IF NOT has_schema_privilege(migration_role.oid, 'logistics', 'USAGE')
        OR has_schema_privilege(migration_role.oid, 'logistics', 'CREATE') THEN
        RAISE EXCEPTION 'nexa_migrator must have only USAGE, not CREATE, on logistics to call the V188 helper';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = active_attempt_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = handoff_token_oid AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'V188 requires forced row-level security to remain enabled on both delivery tables';
    END IF;
    IF NOT has_table_privilege(runtime_role.oid, active_attempt_oid, 'SELECT')
        OR NOT has_table_privilege(runtime_role.oid, active_attempt_oid, 'INSERT')
        OR NOT has_table_privilege(runtime_role.oid, active_attempt_oid, 'UPDATE')
        OR NOT has_table_privilege(runtime_role.oid, active_attempt_oid, 'DELETE')
        OR NOT has_table_privilege(runtime_role.oid, handoff_token_oid, 'SELECT')
        OR NOT has_table_privilege(runtime_role.oid, handoff_token_oid, 'INSERT')
        OR NOT has_table_privilege(runtime_role.oid, handoff_token_oid, 'UPDATE')
        OR NOT has_table_privilege(runtime_role.oid, handoff_token_oid, 'DELETE')
        OR has_table_privilege(runtime_role.oid, active_attempt_oid, 'TRUNCATE')
        OR has_table_privilege(runtime_role.oid, active_attempt_oid, 'REFERENCES')
        OR has_table_privilege(runtime_role.oid, active_attempt_oid, 'TRIGGER')
        OR has_table_privilege(runtime_role.oid, handoff_token_oid, 'TRUNCATE')
        OR has_table_privilege(runtime_role.oid, handoff_token_oid, 'REFERENCES')
        OR has_table_privilege(runtime_role.oid, handoff_token_oid, 'TRIGGER') THEN
        RAISE EXCEPTION 'V188 requires the existing scoped runtime DML grants without table-administration privileges';
    END IF;
    IF has_table_privilege(migration_role.oid, active_attempt_oid, 'SELECT')
        OR has_table_privilege(migration_role.oid, active_attempt_oid, 'INSERT')
        OR has_table_privilege(migration_role.oid, active_attempt_oid, 'UPDATE')
        OR has_table_privilege(migration_role.oid, active_attempt_oid, 'DELETE')
        OR has_table_privilege(migration_role.oid, active_attempt_oid, 'TRUNCATE')
        OR has_table_privilege(migration_role.oid, active_attempt_oid, 'REFERENCES')
        OR has_table_privilege(migration_role.oid, active_attempt_oid, 'TRIGGER')
        OR has_table_privilege(migration_role.oid, handoff_token_oid, 'SELECT')
        OR has_table_privilege(migration_role.oid, handoff_token_oid, 'INSERT')
        OR has_table_privilege(migration_role.oid, handoff_token_oid, 'UPDATE')
        OR has_table_privilege(migration_role.oid, handoff_token_oid, 'DELETE')
        OR has_table_privilege(migration_role.oid, handoff_token_oid, 'TRUNCATE')
        OR has_table_privilege(migration_role.oid, handoff_token_oid, 'REFERENCES')
        OR has_table_privilege(migration_role.oid, handoff_token_oid, 'TRIGGER') THEN
        RAISE EXCEPTION 'V188 must not grant nexa_migrator direct privileges on delivery tables';
    END IF;

    IF to_regprocedure('logistics.apply_v188_buyer_handoff_to_active_attempt()') IS NOT NULL
        OR to_regprocedure('logistics.prevent_delivery_handoff_token_mutation_v188()') IS NOT NULL
        OR EXISTS (
            SELECT 1 FROM pg_attribute
             WHERE attrelid = handoff_token_oid
               AND attname = 'delivery_active_attempt_id'
               AND attnum > 0 AND NOT attisdropped
        )
        OR EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE (conrelid = active_attempt_oid AND conname = 'uq_delivery_active_attempt_scope_binding')
                OR (conrelid = handoff_token_oid AND conname IN (
                    'ck_handoff_token_attempt_binding_v188', 'fk_handoff_token_attempt_v188',
                    'fk_handoff_token_active_attempt_v188'))
        )
        OR to_regclass('logistics.uq_delivery_active_attempt_scope_binding') IS NOT NULL
        OR EXISTS (
            SELECT 1 FROM pg_trigger
             WHERE tgrelid = handoff_token_oid
               AND tgname = 'logistics_delivery_handoff_token_lifecycle_v188'
               AND NOT tgisinternal
        ) THEN
        RAISE EXCEPTION 'V188 DDL artifacts must not exist before preparation';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = handoff_token_oid
           AND conname = 'fk_handoff_token_attempt'
           AND contype = 'f'
           AND confrelid = terminal_attempt_oid
           AND convalidated
    ) OR (SELECT attnotnull FROM pg_attribute
           WHERE attrelid = handoff_token_oid
             AND attname = 'delivery_attempt_id'
             AND attnum > 0 AND NOT attisdropped) IS NOT TRUE THEN
        RAISE EXCEPTION 'V188 requires the original terminal-attempt handoff binding';
    END IF;

    SELECT trigger_row.oid INTO old_trigger_oid
      FROM pg_trigger trigger_row
     WHERE trigger_row.tgrelid = handoff_token_oid
       AND trigger_row.tgname = 'logistics_delivery_handoff_token_lifecycle_v17'
       AND NOT trigger_row.tgisinternal
       AND trigger_row.tgfoid = to_regprocedure('logistics.prevent_delivery_handoff_token_mutation_v17()');
    SELECT index_row.indexrelid INTO active_index_oid
      FROM pg_index index_row
      JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
     WHERE index_row.indrelid = handoff_token_oid
       AND index_class.relname = 'uq_handoff_token_active_delivery_attempt'
       AND index_row.indisunique AND index_row.indisvalid
       AND pg_get_expr(index_row.indpred, index_row.indrelid) LIKE '%status%ACTIVE%';
    IF old_trigger_oid IS NULL OR active_index_oid IS NULL
        OR (SELECT relowner FROM pg_class WHERE oid = active_index_oid) <> handoff_token_owner THEN
        RAISE EXCEPTION 'V188 requires the existing handoff lifecycle trigger and active-token unique index';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION logistics.apply_v188_buyer_handoff_to_active_attempt()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    active_attempt_oid oid;
    handoff_token_oid oid;
    active_attempt_owner oid;
    handoff_token_owner oid;
    trigger_function_grantee record;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO active_attempt_oid, active_attempt_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_active_attempt'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO handoff_token_oid, handoff_token_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_handoff_token'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR active_attempt_oid IS NULL OR handoff_token_oid IS NULL
        OR active_attempt_owner <> handoff_token_owner
        OR current_user <> pg_get_userbyid(active_attempt_owner)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = active_attempt_oid AND relrowsecurity AND relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = handoff_token_oid AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION 'V188 DDL may only run in the direct nexa_migrator session under both existing table owners with forced RLS intact';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_attribute
         WHERE attrelid = handoff_token_oid
           AND attname = 'delivery_active_attempt_id'
           AND attnum > 0 AND NOT attisdropped
    ) OR EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE (conrelid = active_attempt_oid AND conname = 'uq_delivery_active_attempt_scope_binding')
            OR (conrelid = handoff_token_oid AND conname IN (
                'ck_handoff_token_attempt_binding_v188', 'fk_handoff_token_attempt_v188',
                'fk_handoff_token_active_attempt_v188'))
    ) OR to_regclass('logistics.uq_delivery_active_attempt_scope_binding') IS NOT NULL
      OR EXISTS (
          SELECT 1 FROM pg_trigger
           WHERE tgrelid = handoff_token_oid
             AND tgname = 'logistics_delivery_handoff_token_lifecycle_v188'
             AND NOT tgisinternal
      ) OR to_regprocedure('logistics.prevent_delivery_handoff_token_mutation_v188()') IS NOT NULL THEN
        RAISE EXCEPTION 'V188 DDL artifacts are already present';
    END IF;

    ALTER TABLE logistics.delivery_active_attempt
        ADD CONSTRAINT uq_delivery_active_attempt_scope_binding
            UNIQUE (tenant_id, workspace_id, id, delivery_id);

    ALTER TABLE logistics.delivery_handoff_token
        ALTER COLUMN delivery_attempt_id DROP NOT NULL,
        ADD COLUMN delivery_active_attempt_id UUID;

    ALTER TABLE logistics.delivery_handoff_token
        DROP CONSTRAINT fk_handoff_token_attempt,
        ADD CONSTRAINT ck_handoff_token_attempt_binding_v188
            CHECK ((delivery_attempt_id IS NOT NULL) <> (delivery_active_attempt_id IS NOT NULL)),
        ADD CONSTRAINT fk_handoff_token_attempt_v188
            FOREIGN KEY (tenant_id, workspace_id, delivery_attempt_id, delivery_id)
            REFERENCES logistics.delivery_attempt (tenant_id, workspace_id, id, delivery_id),
        ADD CONSTRAINT fk_handoff_token_active_attempt_v188
            FOREIGN KEY (tenant_id, workspace_id, delivery_active_attempt_id, delivery_id)
            REFERENCES logistics.delivery_active_attempt (tenant_id, workspace_id, id, delivery_id);

    DROP INDEX logistics.uq_handoff_token_active_delivery_attempt;
    CREATE UNIQUE INDEX uq_handoff_token_active_delivery_attempt
        ON logistics.delivery_handoff_token
            (tenant_id, workspace_id, delivery_id,
             (COALESCE(delivery_attempt_id, delivery_active_attempt_id)))
        WHERE status = 'ACTIVE';

    DROP TRIGGER logistics_delivery_handoff_token_lifecycle_v17
        ON logistics.delivery_handoff_token;

    CREATE OR REPLACE FUNCTION logistics.prevent_delivery_handoff_token_mutation_v188()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
    AS $trigger$
    BEGIN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Delivery handoff token binding is immutable';
        END IF;

        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
           OR NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
           OR NEW.customer_account_id IS DISTINCT FROM OLD.customer_account_id
           OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
           OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
           OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
           OR NEW.issuer_membership_id IS DISTINCT FROM OLD.issuer_membership_id
           OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
           OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Delivery handoff token binding is immutable';
        END IF;

        IF OLD.delivery_attempt_id IS NULL
           AND OLD.delivery_active_attempt_id IS NOT NULL
           AND NEW.delivery_attempt_id = OLD.delivery_active_attempt_id
           AND NEW.delivery_active_attempt_id IS NULL
           AND (NEW.status IS NOT DISTINCT FROM OLD.status
                OR (OLD.status = 'ACTIVE' AND NEW.status = 'REPLACED')) THEN
            RETURN NEW;
        END IF;

        IF NEW.delivery_attempt_id IS DISTINCT FROM OLD.delivery_attempt_id
           OR NEW.delivery_active_attempt_id IS DISTINCT FROM OLD.delivery_active_attempt_id
           OR OLD.status <> 'ACTIVE'
           OR NEW.status NOT IN ('REPLACED', 'EXPIRED', 'CONSUMED') THEN
            RAISE EXCEPTION 'Delivery handoff token lifecycle transition is invalid';
        END IF;

        RETURN NEW;
    END;
    $trigger$;

    FOR trigger_function_grantee IN
        SELECT DISTINCT acl.grantee, role_row.rolname
          FROM pg_proc function_catalog
          CROSS JOIN LATERAL aclexplode(coalesce(function_catalog.proacl,
                                                 acldefault('f', function_catalog.proowner))) acl
          LEFT JOIN pg_roles role_row ON role_row.oid = acl.grantee
         WHERE function_catalog.oid = 'logistics.prevent_delivery_handoff_token_mutation_v188()'::regprocedure
           AND acl.grantee <> function_catalog.proowner
    LOOP
        IF trigger_function_grantee.grantee = 0 THEN
            EXECUTE 'REVOKE ALL ON FUNCTION logistics.prevent_delivery_handoff_token_mutation_v188() FROM PUBLIC';
        ELSIF trigger_function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION logistics.prevent_delivery_handoff_token_mutation_v188() FROM %I',
                trigger_function_grantee.rolname);
        END IF;
    END LOOP;

    CREATE TRIGGER logistics_delivery_handoff_token_lifecycle_v188
        BEFORE UPDATE OR DELETE ON logistics.delivery_handoff_token
        FOR EACH ROW
        EXECUTE FUNCTION logistics.prevent_delivery_handoff_token_mutation_v188();

    EXECUTE 'REVOKE ALL ON FUNCTION logistics.apply_v188_buyer_handoff_to_active_attempt() FROM PUBLIC, nexa_migrator';
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
     WHERE schema_row.nspname = 'logistics'
       AND relation.relname = 'delivery_active_attempt'
       AND relation.relkind = 'r';
    helper_oid := 'logistics.apply_v188_buyer_handoff_to_active_attempt()'::regprocedure;

    EXECUTE format('ALTER FUNCTION logistics.apply_v188_buyer_handoff_to_active_attempt() OWNER TO %I',
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
            EXECUTE 'REVOKE ALL ON FUNCTION logistics.apply_v188_buyer_handoff_to_active_attempt() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION logistics.apply_v188_buyer_handoff_to_active_attempt() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION logistics.apply_v188_buyer_handoff_to_active_attempt() TO nexa_migrator;
END;
$$;

COMMIT;
