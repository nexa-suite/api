-- Keep recoverable push destinations in the Tenant database as authenticated ciphertext.
-- Only the traceability worker receives the encrypted material and exact invalidation fields.
ALTER TABLE notifications.push_subscription
    ADD COLUMN provider_destination_ciphertext TEXT,
    ADD COLUMN provider_destination_key_version VARCHAR(64),
    ADD CONSTRAINT ck_push_subscription_destination_pair CHECK (
        (provider_destination_ciphertext IS NULL AND provider_destination_key_version IS NULL)
        OR (
            provider_destination_ciphertext IS NOT NULL
            AND provider_destination_key_version IS NOT NULL
            AND length(provider_destination_ciphertext) BETWEEN 39 AND 12000
            AND provider_destination_ciphertext ~ '^[A-Za-z0-9_-]+$'
            AND provider_destination_key_version ~ '^[A-Za-z0-9._-]{1,64}$'
        )
    );

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V44';
    END IF;

    EXECUTE 'GRANT SELECT (provider_destination_ciphertext, provider_destination_key_version) '
         || 'ON TABLE notifications.push_subscription TO nexa_business_traceability_worker';
    EXECUTE 'GRANT UPDATE (status, updated_at, version, provider_destination_ciphertext, '
         || 'provider_destination_key_version) ON TABLE notifications.push_subscription '
         || 'TO nexa_business_traceability_worker';

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        EXECUTE 'GRANT SELECT (provider_destination_ciphertext, provider_destination_key_version) '
             || 'ON TABLE notifications.push_subscription TO nexa_runtime';
        EXECUTE 'GRANT INSERT (provider_destination_ciphertext, provider_destination_key_version) '
             || 'ON TABLE notifications.push_subscription TO nexa_runtime';
        EXECUTE 'GRANT UPDATE (provider_destination_ciphertext, provider_destination_key_version) '
             || 'ON TABLE notifications.push_subscription TO nexa_runtime';
    END IF;
END
$$;
