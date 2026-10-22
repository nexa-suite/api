-- Install fixed, one-shot central Buyer account DDL under the owner shared by
-- client_account and workspace_membership. The restricted migrator stays a
-- direct login and receives only EXECUTE on this no-argument helper.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    account_owner oid;
    membership_owner oid;
    account_owner_super boolean;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V183 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 182 THEN
        RAISE EXCEPTION 'V183 preparation requires central Flyway version 182 to be current';
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
        RAISE EXCEPTION 'V183 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.relowner INTO account_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account'
       AND relation.relkind = 'r';
    SELECT relation.relowner INTO membership_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'workspace_membership'
       AND relation.relkind = 'r';
    IF account_owner IS NULL OR membership_owner IS NULL OR account_owner <> membership_owner
        OR account_owner = runtime_role.oid OR pg_has_role(runtime_role.oid, account_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V183 requires both source tables to share a non-runtime owner';
    END IF;

    SELECT rolsuper INTO account_owner_super FROM pg_roles WHERE oid = account_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> account_owner
        AND NOT pg_has_role(current_role_oid, account_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V183 preparation must run as both table owner or an authorized database administrator';
    END IF;
    IF NOT account_owner_super
        AND (NOT has_schema_privilege(account_owner, 'tenant_management', 'USAGE')
            OR NOT has_schema_privilege(account_owner, 'tenant_management', 'CREATE')
            OR NOT has_schema_privilege(account_owner, 'sales', 'USAGE')) THEN
        RAISE EXCEPTION 'The shared table owner must be able to own a helper in tenant_management and alter sales.client_account';
    END IF;
    IF NOT has_schema_privilege(migration_role.oid, 'tenant_management', 'USAGE') THEN
        RAISE EXCEPTION 'nexa_migrator must have existing USAGE on tenant_management to call the V183 helper';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v183_buyer_account_type_sales_owner_scope()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    account_oid oid;
    membership_oid oid;
    account_owner oid;
    membership_owner oid;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO account_oid, account_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'sales'
       AND relation.relname = 'client_account'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO membership_oid, membership_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'tenant_management'
       AND relation.relname = 'workspace_membership'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR account_oid IS NULL OR membership_oid IS NULL
        OR account_owner <> membership_owner
        OR current_user <> pg_get_userbyid(account_owner) THEN
        RAISE EXCEPTION 'V183 Buyer account DDL may only run as nexa_migrator under the shared table owner';
    END IF;

    ALTER TABLE sales.client_account
        ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'BUSINESS',
        ALTER COLUMN tax_country_code DROP NOT NULL,
        ALTER COLUMN tax_identifier_type DROP NOT NULL,
        ALTER COLUMN tax_identifier_value DROP NOT NULL,
        ADD CONSTRAINT ck_client_account_type
            CHECK (account_type IN ('BUSINESS', 'INDIVIDUAL')),
        ADD CONSTRAINT ck_client_account_tax_by_type
            CHECK (
                (account_type = 'BUSINESS'
                    AND tax_country_code IS NOT NULL
                    AND tax_identifier_type IS NOT NULL
                    AND tax_identifier_value IS NOT NULL)
                OR
                (account_type = 'INDIVIDUAL'
                    AND tax_country_code IS NULL
                    AND tax_identifier_type IS NULL
                    AND tax_identifier_value IS NULL)
            ),
        ADD CONSTRAINT ck_client_account_peru_ruc_format
            CHECK (
                account_type <> 'BUSINESS'
                OR upper(tax_country_code) <> 'PE'
                OR upper(tax_identifier_type) <> 'RUC'
                OR tax_identifier_value ~ '^[0-9]{11}$'
            );

    ALTER TABLE tenant_management.workspace_membership
        ADD CONSTRAINT uq_workspace_membership_tenant_workspace_id
            UNIQUE (tenant_id, workspace_id, id);

    ALTER TABLE sales.client_account
        ADD CONSTRAINT fk_client_account_sales_owner_scope_membership
            FOREIGN KEY (tenant_id, workspace_id, sales_owner_membership_id)
            REFERENCES tenant_management.workspace_membership (tenant_id, workspace_id, id)
            ON DELETE RESTRICT;

    CREATE INDEX ix_client_account_sales_owner_scope
        ON sales.client_account (tenant_id, workspace_id, sales_owner_membership_id)
        WHERE sales_owner_membership_id IS NOT NULL;

    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v183_buyer_account_type_sales_owner_scope() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    account_owner oid;
    helper_oid oid;
    function_grantee record;
BEGIN
    SELECT relowner INTO account_owner FROM pg_class WHERE oid = 'sales.client_account'::regclass;
    helper_oid := 'tenant_management.apply_v183_buyer_account_type_sales_owner_scope()'::regprocedure;

    EXECUTE format('ALTER FUNCTION tenant_management.apply_v183_buyer_account_type_sales_owner_scope() OWNER TO %I',
        pg_get_userbyid(account_owner));

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
            EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v183_buyer_account_type_sales_owner_scope() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v183_buyer_account_type_sales_owner_scope() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION tenant_management.apply_v183_buyer_account_type_sales_owner_scope() TO nexa_migrator;
END;
$$;

COMMIT;
