DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V15';
    END IF;

    EXECUTE 'GRANT USAGE ON SCHEMA notifications, tenant_management TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, recipient_membership_id, event_id, category, title, message, deep_link, subject_type, subject_id, created_at, read_at) ON TABLE notifications.inbox_item TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (workspace_id, event_category, channel, enabled) ON TABLE tenant_management.notification_preference TO nexa_business_traceability_worker';
END
$$;
