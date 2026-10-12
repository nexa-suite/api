DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_payments_worker') THEN
        RAISE EXCEPTION 'nexa_payments_worker role must be provisioned before Tenant migration V21';
    END IF;

    -- ON CONFLICT targets require SELECT. Grant only the conflict keys needed
    -- for callback payment-event, audit, and outbox writes, not event metadata.
    EXECUTE 'GRANT SELECT (payment_id, event_key) ON TABLE payments.payment_event TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (id) ON TABLE audit.event TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (event_id) ON TABLE integration.outbox_event TO nexa_payments_worker';
END
$$;
