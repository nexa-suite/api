CREATE TABLE catalog_management.command_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, operation, idempotency_key),
    CONSTRAINT fk_catalog_command_idempotency_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_catalog_command_idempotency_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_catalog_command_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_catalog_command_idempotency_created
    ON catalog_management.command_idempotency (tenant_id, workspace_id, created_at);
