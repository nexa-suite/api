-- Durable provisioning state is separate from tenant/workspace ACTIVE status.
CREATE TABLE tenant_management.tenant_business_database_provisioning_task (
    task_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    database_port INTEGER,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    failure_code VARCHAR(64),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_business_database_provisioning_tenant UNIQUE (tenant_id),
    CONSTRAINT fk_tenant_business_database_provisioning_workspace
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_business_database_provisioning_status
        CHECK (status IN ('PENDING', 'LEASED', 'FAILED', 'READY')),
    CONSTRAINT ck_tenant_business_database_provisioning_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_tenant_business_database_provisioning_port
        CHECK (database_port IS NULL OR database_port BETWEEN 1024 AND 65535),
    CONSTRAINT ck_tenant_business_database_provisioning_lease CHECK (
        (status = 'LEASED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'LEASED' AND claim_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_tenant_business_database_provisioning_completion CHECK (
        (status = 'READY' AND completed_at IS NOT NULL)
        OR (status <> 'READY' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_tenant_business_database_provisioning_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_tenant_business_database_provisioning_port
    ON tenant_management.tenant_business_database_provisioning_task (database_port)
    WHERE database_port IS NOT NULL;
CREATE INDEX ix_tenant_business_database_provisioning_due
    ON tenant_management.tenant_business_database_provisioning_task (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_tenant_business_database_provisioning_expired_lease
    ON tenant_management.tenant_business_database_provisioning_task (lease_until, created_at)
    WHERE status = 'LEASED';

ALTER TABLE tenant_management.tenant_business_database_provisioning_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.tenant_business_database_provisioning_task FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_business_database_provisioning_task_scope
    ON tenant_management.tenant_business_database_provisioning_task
    USING (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
        OR current_setting('app.cross_scope_workspace_scan', true) = 'true'
    )
    WITH CHECK (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
        OR current_setting('app.cross_scope_workspace_scan', true) = 'true'
    );
CREATE POLICY tenant_business_database_provisioning_task_internal_operator
    ON tenant_management.tenant_business_database_provisioning_task
    FOR SELECT
    USING (coalesce(current_setting('app.current_internal_operator_id', true), '')
        ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

REVOKE ALL PRIVILEGES ON tenant_management.tenant_business_database_provisioning_task FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON tenant_management.tenant_business_database_provisioning_task TO nexa_runtime;
        GRANT UPDATE (status, attempt_count, database_port, claim_token, lease_until, next_attempt_at,
            failure_code, completed_at, updated_at, version)
            ON tenant_management.tenant_business_database_provisioning_task TO nexa_runtime;
    END IF;
END;
$$;

-- Tenant owns one physical database. Workspace scope anchors are projected
-- independently so later Workspaces reuse that database without changing the
-- Tenant binding lifecycle or provisioning another database.
CREATE TABLE tenant_management.tenant_business_database_workspace_anchor_task (
    task_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    failure_code VARCHAR(64),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_business_database_workspace_anchor_scope UNIQUE (tenant_id, workspace_id),
    CONSTRAINT fk_tenant_business_database_workspace_anchor_workspace
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_business_database_workspace_anchor_status
        CHECK (status IN ('PENDING', 'LEASED', 'FAILED', 'READY')),
    CONSTRAINT ck_tenant_business_database_workspace_anchor_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_tenant_business_database_workspace_anchor_lease CHECK (
        (status = 'LEASED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'LEASED' AND claim_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_tenant_business_database_workspace_anchor_completion CHECK (
        (status = 'READY' AND completed_at IS NOT NULL)
        OR (status <> 'READY' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_tenant_business_database_workspace_anchor_version CHECK (version >= 0)
);

CREATE INDEX ix_tenant_business_database_workspace_anchor_due
    ON tenant_management.tenant_business_database_workspace_anchor_task (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_tenant_business_database_workspace_anchor_expired_lease
    ON tenant_management.tenant_business_database_workspace_anchor_task (lease_until, created_at)
    WHERE status = 'LEASED';

ALTER TABLE tenant_management.tenant_business_database_workspace_anchor_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.tenant_business_database_workspace_anchor_task FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_business_database_workspace_anchor_task_scope
    ON tenant_management.tenant_business_database_workspace_anchor_task
    USING (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
        OR current_setting('app.cross_scope_workspace_scan', true) = 'true'
    )
    WITH CHECK (
        (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
            AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
        OR current_setting('app.cross_scope_workspace_scan', true) = 'true'
    );
CREATE POLICY tenant_business_database_workspace_anchor_task_internal_operator
    ON tenant_management.tenant_business_database_workspace_anchor_task
    FOR SELECT
    USING (coalesce(current_setting('app.current_internal_operator_id', true), '')
        ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

CREATE POLICY tenant_business_database_binding_internal_operator
    ON tenant_management.tenant_business_database_binding
    FOR SELECT
    USING (coalesce(current_setting('app.current_internal_operator_id', true), '')
        ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

REVOKE ALL PRIVILEGES ON tenant_management.tenant_business_database_workspace_anchor_task FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON tenant_management.tenant_business_database_workspace_anchor_task TO nexa_runtime;
        GRANT UPDATE (status, attempt_count, claim_token, lease_until, next_attempt_at,
            failure_code, completed_at, updated_at, version)
            ON tenant_management.tenant_business_database_workspace_anchor_task TO nexa_runtime;
    END IF;
END;
$$;
