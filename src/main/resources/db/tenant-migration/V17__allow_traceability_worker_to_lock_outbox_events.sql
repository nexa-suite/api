DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V17';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION integration.reject_outbox_event_identity_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Tenant outbox event identity is immutable' USING ERRCODE = '23514';
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenant_outbox_event_identity_immutable
BEFORE UPDATE OF event_id ON integration.outbox_event
FOR EACH ROW EXECUTE FUNCTION integration.reject_outbox_event_identity_update();

GRANT UPDATE (event_id) ON TABLE integration.outbox_event TO nexa_business_traceability_worker;
