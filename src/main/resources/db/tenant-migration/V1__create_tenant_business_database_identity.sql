CREATE SCHEMA IF NOT EXISTS nexa_platform;

CREATE TABLE nexa_platform.tenant_business_database_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
    tenant_id UUID NOT NULL UNIQUE,
    database_identity UUID NOT NULL UNIQUE,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT ck_tenant_business_database_identity_singleton CHECK (singleton)
);

GRANT USAGE ON SCHEMA nexa_platform TO nexa_runtime;
GRANT SELECT ON nexa_platform.tenant_business_database_identity TO nexa_runtime;
