-- V1 Tenant & Access Governance invariants.
-- Fail before adding constraints when existing data cannot satisfy the target
-- contract. No repair or silent reassignment is performed by this migration.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tenant_management.workspace
        GROUP BY tenant_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V84 cannot enforce Tenant 1:1 Workspace: duplicate workspaces exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tenant_management.membership_role_definition assignment
        JOIN tenant_management.role_definition role_definition ON role_definition.id = assignment.role_id
        WHERE role_definition.code = 'company_owner'
        GROUP BY assignment.tenant_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V84 cannot enforce one Company Owner: duplicate assignments exist';
    END IF;
END;
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_management_workspace_tenant
    ON tenant_management.workspace (tenant_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_management_company_owner
    ON tenant_management.membership_role_definition (tenant_id)
    WHERE role_id = '7b5832ae-264f-38a1-8ee6-334bc641e42b'::uuid;
