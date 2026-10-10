\set ON_ERROR_STOP on
SELECT set_config('nexa.local.tenant_id', :'tenant_id', false) AS _tenant_id \gset
SELECT set_config('nexa.local.workspace_id', :'workspace_id', false) AS _workspace_id \gset
SELECT set_config('nexa.local.database_identity', :'database_identity', false) AS _database_identity \gset

DO $seed$
DECLARE
    expected_tenant UUID := current_setting('nexa.local.tenant_id')::UUID;
    expected_workspace UUID := current_setting('nexa.local.workspace_id')::UUID;
    expected_database UUID := current_setting('nexa.local.database_identity')::UUID;
    identity_rows BIGINT;
    matching_anchor_rows BIGINT;
BEGIN
    IF current_user <> 'nexa_tenant_bootstrap_admin' THEN
        RAISE EXCEPTION 'Tenant identity and scope must be seeded by the isolated bootstrap administrator';
    END IF;

    SELECT count(*) INTO identity_rows FROM nexa_platform.tenant_business_database_identity;
    IF identity_rows > 1 OR (identity_rows = 1 AND NOT EXISTS (
        SELECT 1 FROM nexa_platform.tenant_business_database_identity
        WHERE singleton = TRUE AND tenant_id = expected_tenant AND database_identity = expected_database
    )) THEN
        RAISE EXCEPTION 'Tenant database identity already belongs to another binding';
    END IF;
    IF identity_rows = 0 THEN
        INSERT INTO nexa_platform.tenant_business_database_identity (singleton, tenant_id, database_identity)
        VALUES (TRUE, expected_tenant, expected_database);
    END IF;

    SELECT count(*) INTO matching_anchor_rows
      FROM nexa_platform.tenant_workspace_scope_anchor
     WHERE tenant_id = expected_tenant AND workspace_id = expected_workspace;
    IF matching_anchor_rows = 0 THEN
        INSERT INTO nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id)
        VALUES (expected_tenant, expected_workspace)
        ON CONFLICT (tenant_id, workspace_id) DO NOTHING;
        SELECT count(*) INTO matching_anchor_rows
          FROM nexa_platform.tenant_workspace_scope_anchor
         WHERE tenant_id = expected_tenant AND workspace_id = expected_workspace;
    END IF;
    IF matching_anchor_rows <> 1 THEN
        RAISE EXCEPTION 'Tenant Workspace scope anchor could not be verified';
    END IF;
END
$seed$;
