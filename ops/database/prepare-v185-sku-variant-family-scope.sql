-- Install a fixed, one-shot helper owned by the common Product Variant and
-- Sellable SKU owner. The restricted migrator receives EXECUTE only.
BEGIN;

DO $$
DECLARE
    current_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    current_role_super boolean;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    sku_oid oid;
    variant_oid oid;
    sku_owner oid;
    variant_owner oid;
    owner_super boolean;
    flyway_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime', 'nexa_tenant_database_provisioner',
                        'nexa_internal_operator', 'nexa_internal_operator_login') THEN
        RAISE EXCEPTION 'V185 preparation must run outside application, migration, and worker sessions';
    END IF;

    SELECT max(version::integer) INTO flyway_version
      FROM public.flyway_schema_history
     WHERE success;
    IF coalesce(flyway_version, 0) <> 184 THEN
        RAISE EXCEPTION 'V185 preparation requires central Flyway version 184 to be current';
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
        RAISE EXCEPTION 'V185 requires the restricted direct nexa_runtime login';
    END IF;

    SELECT relation.oid, relation.relowner
      INTO sku_oid, sku_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'catalog_management'
       AND relation.relname = 'sellable_sku'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO variant_oid, variant_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'catalog_management'
       AND relation.relname = 'product_variant'
       AND relation.relkind = 'r';
    IF sku_oid IS NULL OR variant_oid IS NULL OR sku_owner <> variant_owner
        OR sku_owner = runtime_role.oid OR pg_has_role(runtime_role.oid, sku_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V185 requires sellable_sku and product_variant to share a non-runtime owner';
    END IF;

    SELECT rolsuper INTO owner_super FROM pg_roles WHERE oid = sku_owner;
    SELECT rolsuper INTO current_role_super FROM pg_roles WHERE oid = current_role_oid;
    IF NOT current_role_super AND current_role_oid <> sku_owner
        AND NOT pg_has_role(current_role_oid, sku_owner, 'MEMBER') THEN
        RAISE EXCEPTION 'V185 preparation must run as the shared table owner or an authorized database administrator';
    END IF;
    IF NOT owner_super
        AND (NOT has_schema_privilege(sku_owner, 'tenant_management', 'USAGE')
            OR NOT has_schema_privilege(sku_owner, 'tenant_management', 'CREATE')
            OR NOT has_schema_privilege(sku_owner, 'catalog_management', 'USAGE')
            OR NOT has_table_privilege(sku_owner, variant_oid, 'REFERENCES')) THEN
        RAISE EXCEPTION 'The shared table owner must own a helper in tenant_management and reference the Product Variant table';
    END IF;
    IF NOT has_schema_privilege(migration_role.oid, 'tenant_management', 'USAGE') THEN
        RAISE EXCEPTION 'nexa_migrator must have existing USAGE on tenant_management to call the V185 helper';
    END IF;

    IF (SELECT count(*) FROM pg_attribute
         WHERE attrelid = sku_oid
           AND attname = ANY (ARRAY['tenant_id', 'workspace_id', 'family_id', 'variant_id'])
           AND attnum > 0 AND NOT attisdropped) <> 4
        OR (SELECT count(*) FROM pg_attribute
             WHERE attrelid = variant_oid
               AND attname = ANY (ARRAY['tenant_id', 'workspace_id', 'family_id', 'id'])
               AND attnum > 0 AND NOT attisdropped) <> 4 THEN
        RAISE EXCEPTION 'V185 requires the existing Tenant, Workspace, family, and variant key columns';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE (conrelid = variant_oid AND conname = 'uq_product_variant_scope_family_id')
            OR (conrelid = sku_oid AND conname = 'fk_sellable_sku_variant_family_scope')
    ) THEN
        RAISE EXCEPTION 'V185 constraints must not exist before their Flyway migration';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.apply_v185_sku_variant_family_scope()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    sku_oid oid;
    variant_oid oid;
    sku_owner oid;
    variant_owner oid;
    invalid_link RECORD;
BEGIN
    SELECT relation.oid, relation.relowner
      INTO sku_oid, sku_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'catalog_management'
       AND relation.relname = 'sellable_sku'
       AND relation.relkind = 'r';
    SELECT relation.oid, relation.relowner
      INTO variant_oid, variant_owner
      FROM pg_class relation
      JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
     WHERE schema_row.nspname = 'catalog_management'
       AND relation.relname = 'product_variant'
       AND relation.relkind = 'r';
    IF session_user <> 'nexa_migrator'
        OR sku_oid IS NULL OR variant_oid IS NULL
        OR sku_owner <> variant_owner
        OR current_user <> pg_get_userbyid(sku_owner) THEN
        RAISE EXCEPTION 'V185 SKU family-scope DDL may only run as nexa_migrator under the common table owner';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE (conrelid = variant_oid AND conname = 'uq_product_variant_scope_family_id')
            OR (conrelid = sku_oid AND conname = 'fk_sellable_sku_variant_family_scope')
    ) THEN
        RAISE EXCEPTION 'V185 SKU family-scope constraints are already present';
    END IF;

    SELECT sku.tenant_id,
           sku.workspace_id,
           sku.id AS sku_id,
           sku.family_id,
           sku.variant_id,
           variant.family_id AS variant_family_id
      INTO invalid_link
      FROM catalog_management.sellable_sku sku
      LEFT JOIN catalog_management.product_variant variant
        ON variant.tenant_id = sku.tenant_id
       AND variant.workspace_id = sku.workspace_id
       AND variant.id = sku.variant_id
     WHERE sku.variant_id IS NOT NULL
       AND (variant.id IS NULL OR variant.family_id IS DISTINCT FROM sku.family_id)
     ORDER BY sku.tenant_id, sku.workspace_id, sku.id
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'V185 cannot enforce SKU variant family scope: tenant_id %, workspace_id %, sku_id %, family_id %, variant_id %, variant_family_id %',
            invalid_link.tenant_id,
            invalid_link.workspace_id,
            invalid_link.sku_id,
            invalid_link.family_id,
            invalid_link.variant_id,
            invalid_link.variant_family_id;
    END IF;

    ALTER TABLE catalog_management.product_variant
        ADD CONSTRAINT uq_product_variant_scope_family_id
        UNIQUE (tenant_id, workspace_id, family_id, id);

    ALTER TABLE catalog_management.sellable_sku
        ADD CONSTRAINT fk_sellable_sku_variant_family_scope
        FOREIGN KEY (tenant_id, workspace_id, family_id, variant_id)
        REFERENCES catalog_management.product_variant (tenant_id, workspace_id, family_id, id)
        MATCH SIMPLE;

    EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v185_sku_variant_family_scope() FROM PUBLIC, nexa_migrator';
END;
$function$;

DO $$
DECLARE
    sku_owner oid;
    helper_oid oid;
    function_grantee record;
BEGIN
    SELECT relowner INTO sku_owner
      FROM pg_class
     WHERE oid = 'catalog_management.sellable_sku'::regclass;
    helper_oid := 'tenant_management.apply_v185_sku_variant_family_scope()'::regprocedure;

    EXECUTE format('ALTER FUNCTION tenant_management.apply_v185_sku_variant_family_scope() OWNER TO %I',
        pg_get_userbyid(sku_owner));

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
            EXECUTE 'REVOKE ALL ON FUNCTION tenant_management.apply_v185_sku_variant_family_scope() FROM PUBLIC';
        ELSIF function_grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION tenant_management.apply_v185_sku_variant_family_scope() FROM %I',
                function_grantee.rolname);
        END IF;
    END LOOP;

    GRANT EXECUTE ON FUNCTION tenant_management.apply_v185_sku_variant_family_scope() TO nexa_migrator;
END;
$$;

COMMIT;
