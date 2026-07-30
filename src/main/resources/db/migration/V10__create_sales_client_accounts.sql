CREATE SCHEMA IF NOT EXISTS sales;

CREATE TABLE sales.client_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL,
    business_name VARCHAR(200) NOT NULL,
    commercial_name VARCHAR(200) NOT NULL,
    tax_country_code VARCHAR(2) NOT NULL,
    tax_identifier_type VARCHAR(32) NOT NULL,
    tax_identifier_value VARCHAR(64) NOT NULL,
    segment VARCHAR(80) NOT NULL,
    contact_person VARCHAR(160) NOT NULL,
    contact_email VARCHAR(254) NOT NULL,
    phone VARCHAR(64) NOT NULL,
    delivery_profile TEXT NOT NULL,
    payment_condition VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_client_account_tenant FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant(id),
    CONSTRAINT fk_client_account_workspace FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace(id),
    CONSTRAINT uq_client_account_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_client_account_tenant_tax UNIQUE (tenant_id, tax_country_code, tax_identifier_type, tax_identifier_value)
);

CREATE TABLE sales.client_account_membership (
    client_account_id UUID NOT NULL,
    workspace_membership_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_account_id, workspace_membership_id),
    CONSTRAINT uq_client_account_buyer_membership UNIQUE (workspace_membership_id),
    CONSTRAINT fk_client_membership_account FOREIGN KEY (client_account_id) REFERENCES sales.client_account(id),
    CONSTRAINT fk_client_membership_membership FOREIGN KEY (workspace_membership_id) REFERENCES tenant_management.workspace_membership(id),
    CONSTRAINT fk_client_membership_tenant FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant(id),
    CONSTRAINT fk_client_membership_workspace FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace(id)
);

CREATE INDEX ix_client_account_scope ON sales.client_account (tenant_id, workspace_id, status);
