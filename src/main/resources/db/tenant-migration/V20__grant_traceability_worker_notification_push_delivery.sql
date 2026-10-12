DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V20';
    END IF;

    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, recipient_membership_id, installation_id, platform, surface, status, created_at, updated_at, version) ON TABLE notifications.push_subscription TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, subscription_id, event_id, status, claim_token, lease_until) ON TABLE notifications.push_delivery_claim TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (tenant_id, workspace_id, subscription_id, event_id, delivery_key, status, claim_token, lease_until, created_at, updated_at) ON TABLE notifications.push_delivery_claim TO nexa_business_traceability_worker';
    EXECUTE 'GRANT UPDATE (status, claim_token, lease_until, updated_at) ON TABLE notifications.push_delivery_claim TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, subscription_id, event_id, status, attempt_number) ON TABLE notifications.push_delivery_attempt TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, subscription_id, event_id, event_type, status, provider_code, error, attempt_number, created_at) ON TABLE notifications.push_delivery_attempt TO nexa_business_traceability_worker';
END
$$;
