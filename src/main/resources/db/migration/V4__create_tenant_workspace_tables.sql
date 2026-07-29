CREATE TABLE tenant_management.tenant (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(80) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE tenant_management.workspace (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_workspace_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT uq_workspace_tenant_slug UNIQUE (tenant_id, slug)
);

CREATE TABLE tenant_management.workspace_membership (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_membership_workspace
        FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace (id),
    CONSTRAINT fk_membership_user
        FOREIGN KEY (user_id) REFERENCES iam.user_account (id),
    CONSTRAINT uq_membership_workspace_user UNIQUE (workspace_id, user_id)
);
