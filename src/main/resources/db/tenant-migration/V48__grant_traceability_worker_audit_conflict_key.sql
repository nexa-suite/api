DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V48';
    END IF;

    -- The idempotent audit insert uses ON CONFLICT (id); do not expose event metadata.
    GRANT SELECT (id) ON TABLE audit.event TO nexa_business_traceability_worker;
END
$$;
