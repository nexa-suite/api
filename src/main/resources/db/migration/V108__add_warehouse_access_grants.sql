CREATE TABLE tenant_management.warehouse_access_grant (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    changed_by_membership_id UUID NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_warehouse_access_grant PRIMARY KEY (tenant_id, workspace_id, membership_id, warehouse_id),
    CONSTRAINT fk_warehouse_access_grant_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_warehouse_access_grant_membership FOREIGN KEY (workspace_id, membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_warehouse_access_grant_actor FOREIGN KEY (workspace_id, changed_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_warehouse_access_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_warehouse_access_grant_version CHECK (version >= 0)
);

CREATE INDEX ix_warehouse_access_grant_membership_active
    ON tenant_management.warehouse_access_grant (tenant_id, workspace_id, membership_id, warehouse_id)
    WHERE status = 'ACTIVE';

ALTER TABLE tenant_management.warehouse_access_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.warehouse_access_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY v108_warehouse_access_grant_scope ON tenant_management.warehouse_access_grant
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_management.warehouse_access_grant TO nexa_runtime;
    END IF;
END;
$$;
