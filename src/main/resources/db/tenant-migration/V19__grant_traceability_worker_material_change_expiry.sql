DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V19';
    END IF;
END
$$;

-- The purchase-request expiry trigger reads only its scope and open-state
-- predicate, then writes only the resolution fields of matching proposals.
GRANT SELECT (tenant_id, workspace_id, purchase_request_id, status)
    ON TABLE sales.purchase_request_material_change
    TO nexa_business_traceability_worker;

GRANT UPDATE (status, resolved_at, resolved_request_version)
    ON TABLE sales.purchase_request_material_change
    TO nexa_business_traceability_worker;
