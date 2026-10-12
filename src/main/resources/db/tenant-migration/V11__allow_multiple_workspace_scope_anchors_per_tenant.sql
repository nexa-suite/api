-- A Tenant has one physical database and may have multiple Workspaces.
-- Keep the Workspace id globally unique while allowing sibling anchors.
ALTER TABLE nexa_platform.tenant_workspace_scope_anchor
    DROP CONSTRAINT uq_tenant_workspace_scope_anchor_tenant;
