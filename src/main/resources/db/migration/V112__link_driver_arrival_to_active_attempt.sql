-- Arrival is an explicit, immutable event for one current Driver Attempt.
-- The attempt can move from delivery_active_attempt to delivery_attempt, so
-- this event retains its opaque id without a foreign key to either lifecycle table.
ALTER TABLE logistics.delivery_event
    ADD COLUMN attempt_id UUID;

ALTER TABLE logistics.delivery_event
    ADD CONSTRAINT ck_driver_arrival_attempt_required
        CHECK (event_type <> 'DRIVER_ARRIVED' OR attempt_id IS NOT NULL);

CREATE UNIQUE INDEX uq_delivery_event_driver_arrival_attempt
    ON logistics.delivery_event (tenant_id, workspace_id, delivery_id, attempt_id)
    WHERE event_type = 'DRIVER_ARRIVED';

CREATE FUNCTION logistics.require_current_driver_arrival_attempt()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_type = 'DRIVER_ARRIVED' AND NOT EXISTS (
        SELECT 1
        FROM logistics.delivery_active_attempt a
        JOIN logistics.delivery_assignment d
          ON d.tenant_id = a.tenant_id
         AND d.workspace_id = a.workspace_id
         AND d.delivery_id = a.delivery_id
         AND d.responsible_membership_id = a.started_by_membership_id
        WHERE a.tenant_id = NEW.tenant_id
          AND a.workspace_id = NEW.workspace_id
          AND a.delivery_id = NEW.delivery_id
          AND a.id = NEW.attempt_id
          AND a.started_by_membership_id = NEW.actor_membership_id
    ) THEN
        RAISE EXCEPTION 'Driver arrival requires the current assigned attempt';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER logistics_delivery_arrival_current_attempt
    BEFORE INSERT ON logistics.delivery_event
    FOR EACH ROW
    EXECUTE FUNCTION logistics.require_current_driver_arrival_attempt();
