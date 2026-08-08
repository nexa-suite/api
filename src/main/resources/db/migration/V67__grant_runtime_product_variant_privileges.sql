-- V65 introduced a runtime-reconciled catalog projection after the original
-- catalog grants in V59. Keep the runtime principal least-privileged while
-- allowing the deterministic seed reconciliation and variant commands.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT USAGE ON SCHEMA catalog_management TO nexa_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE
            ON TABLE catalog_management.product_variant TO nexa_runtime;
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA catalog_management
                 GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexa_runtime';
    END IF;
END;
$$;
