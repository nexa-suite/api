ALTER TABLE tenant_management.tenant_business_database_binding
    ADD COLUMN verified_schema_manifest_sha256 VARCHAR(64);

ALTER TABLE tenant_management.tenant_business_database_binding
    ADD CONSTRAINT ck_tenant_business_database_binding_schema_manifest_sha256
        CHECK (verified_schema_manifest_sha256 IS NULL
            OR verified_schema_manifest_sha256 ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN tenant_management.tenant_business_database_binding.verified_schema_manifest_sha256 IS
    'Provisioner evidence for the exact active Tenant capability manifest and immutable SQL assets validated before READY; not continuous database integrity proof.';
