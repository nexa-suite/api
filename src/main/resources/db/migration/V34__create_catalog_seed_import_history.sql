CREATE TABLE catalog_management.seed_import_history (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    seed_version VARCHAR(32) NOT NULL,
    seed_checksum CHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, seed_version),
    CONSTRAINT fk_seed_import_history_workspace
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id)
);

CREATE INDEX ix_seed_import_history_checksum
    ON catalog_management.seed_import_history (seed_checksum, imported_at DESC);
