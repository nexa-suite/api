CREATE TABLE tenant_management.tenant_business_database_binding (
    tenant_id UUID PRIMARY KEY,
    database_identity UUID NOT NULL UNIQUE,
    credential_secret_reference VARCHAR(512) NOT NULL UNIQUE,
    lifecycle_state VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_tenant_business_database_binding_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_business_database_binding_state
        CHECK (lifecycle_state IN ('PROVISIONING', 'READY', 'SUSPENDED', 'FAILED')),
    CONSTRAINT ck_tenant_business_database_binding_version CHECK (version >= 0),
    CONSTRAINT ck_tenant_business_database_binding_secret_reference
        CHECK (length(btrim(credential_secret_reference)) > 0)
);

ALTER TABLE tenant_management.tenant_business_database_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.tenant_business_database_binding FORCE ROW LEVEL SECURITY;

CREATE POLICY v102_tenant_scope
    ON tenant_management.tenant_business_database_binding
    USING (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT ON tenant_management.tenant_business_database_binding TO nexa_runtime;
    END IF;
END
$$;
