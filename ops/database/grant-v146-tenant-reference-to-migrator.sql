-- Run once as an authorized database provisioner before nexa_migrator applies V146.
-- This is the only new object privilege V146 needs from the central Tenant registry.
DO $$
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime') THEN
        RAISE EXCEPTION 'V146 Tenant reference grant must be provisioned outside runtime and migrator sessions';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_migrator') THEN
        RAISE EXCEPTION 'nexa_migrator must exist before the V146 Tenant reference grant is provisioned';
    END IF;

    IF to_regclass('tenant_management.tenant') IS NULL THEN
        RAISE EXCEPTION 'tenant_management.tenant must exist before the V146 Tenant reference grant is provisioned';
    END IF;
END;
$$;

GRANT REFERENCES ON TABLE tenant_management.tenant TO nexa_migrator;
