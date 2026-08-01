CREATE TABLE tenant_management.organization_registration (
    id UUID PRIMARY KEY,
    legal_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    normalized_legal_name VARCHAR(160) NOT NULL,
    business_identifier VARCHAR(80),
    operation_category VARCHAR(80) NOT NULL,
    storage_site_name VARCHAR(160) NOT NULL,
    storage_site_address VARCHAR(240) NOT NULL,
    founder_email VARCHAR(254) NOT NULL,
    founder_display_name VARCHAR(160) NOT NULL,
    workspace_name VARCHAR(160) NOT NULL,
    workspace_slug VARCHAR(80) NOT NULL,
    reference_plan VARCHAR(32) NOT NULL,
    terms_version VARCHAR(32) NOT NULL,
    terms_accepted_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    rejection_reason VARCHAR(500),
    tenant_id UUID,
    workspace_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_organization_registration_slug UNIQUE (workspace_slug),
    CONSTRAINT ck_organization_registration_status CHECK (status IN ('DRAFT', 'PENDING_ACTIVATION', 'ACTIVE', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_organization_registration_plan CHECK (reference_plan IN ('Starter', 'Standard', 'Professional', 'Enterprise')),
    CONSTRAINT fk_registration_tenant FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant(id),
    CONSTRAINT fk_registration_workspace FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace(id)
);
CREATE INDEX ix_organization_registration_status ON tenant_management.organization_registration (status, created_at);
CREATE INDEX ix_organization_registration_founder ON tenant_management.organization_registration (founder_email);
CREATE UNIQUE INDEX uq_pending_registration_identity
    ON tenant_management.organization_registration (normalized_legal_name, founder_email)
    WHERE status IN ('DRAFT', 'PENDING_ACTIVATION');
