DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V18';
    END IF;
END
$$;

GRANT SELECT (tenant_id, workspace_id, source_state, source_version, expiry_days, snapshot_revision)
    ON TABLE nexa_platform.purchase_request_expiry_policy_snapshot
    TO nexa_business_traceability_worker;

GRANT SELECT (tenant_id, workspace_id, commercial_commitment_id, status, version)
    ON TABLE warehouse.inventory_backing
    TO nexa_business_traceability_worker;

GRANT UPDATE (status, released_at, release_reason, updated_at, version)
    ON TABLE warehouse.inventory_backing
    TO nexa_business_traceability_worker;
