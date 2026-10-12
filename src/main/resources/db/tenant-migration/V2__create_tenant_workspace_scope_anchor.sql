CREATE TABLE nexa_platform.tenant_workspace_scope_anchor (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    CONSTRAINT pk_tenant_workspace_scope_anchor PRIMARY KEY (tenant_id, workspace_id),
    CONSTRAINT uq_tenant_workspace_scope_anchor_tenant UNIQUE (tenant_id),
    CONSTRAINT uq_tenant_workspace_scope_anchor_workspace UNIQUE (workspace_id),
    CONSTRAINT fk_tenant_workspace_scope_anchor_database_identity
        FOREIGN KEY (tenant_id)
        REFERENCES nexa_platform.tenant_business_database_identity (tenant_id)
        ON DELETE RESTRICT
);

COMMENT ON TABLE nexa_platform.tenant_workspace_scope_anchor IS
    'Provisioned Tenant and Workspace identifiers for local scope validation; this metadata does not authorize access.';

REVOKE ALL PRIVILEGES ON TABLE nexa_platform.tenant_workspace_scope_anchor FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE nexa_platform.tenant_workspace_scope_anchor FROM nexa_runtime;
GRANT USAGE ON SCHEMA nexa_platform TO nexa_runtime;
GRANT SELECT ON nexa_platform.tenant_workspace_scope_anchor TO nexa_runtime;
