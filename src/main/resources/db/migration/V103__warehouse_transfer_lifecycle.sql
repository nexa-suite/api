-- Warehouse transfers are requests until dispatch. Dispatch removes stock from
-- the source; receipt is the only operation that adds it to the destination.
DROP TRIGGER warehouse_inventory_transfer_append_only ON warehouse.inventory_transfer;

ALTER TABLE warehouse.inventory_transfer
    DROP CONSTRAINT ck_inventory_transfer_status,
    DROP CONSTRAINT ck_inventory_transfer_quantity,
    DROP CONSTRAINT ck_inventory_transfer_versions,
    ALTER COLUMN destination_lot_id DROP NOT NULL,
    ALTER COLUMN transferred_quantity SET DEFAULT 0,
    ALTER COLUMN source_quantity_after DROP NOT NULL,
    ALTER COLUMN source_version_after DROP NOT NULL,
    ALTER COLUMN destination_quantity_before DROP NOT NULL,
    ALTER COLUMN destination_quantity_after DROP NOT NULL,
    ALTER COLUMN destination_version_after DROP NOT NULL,
    ALTER COLUMN completed_at DROP NOT NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN dispatched_at TIMESTAMPTZ,
    ADD COLUMN received_at TIMESTAMPTZ,
    ADD COLUMN source_lot_status_at_dispatch VARCHAR(16);

UPDATE warehouse.inventory_transfer
SET status = 'RECEIVED', received_at = completed_at;

ALTER TABLE warehouse.inventory_transfer
    ALTER COLUMN transferred_quantity SET NOT NULL,
    ADD CONSTRAINT ck_inventory_transfer_status CHECK (
        (status = 'REQUESTED'
            AND transferred_quantity = 0
            AND dispatched_at IS NULL
            AND received_at IS NULL
            AND destination_lot_id IS NULL
            AND source_quantity_after IS NULL
            AND source_version_after IS NULL
            AND destination_quantity_before IS NULL
            AND destination_quantity_after IS NULL
            AND destination_version_after IS NULL
            AND completed_at IS NULL
            AND source_lot_status_at_dispatch IS NULL)
        OR
        (status = 'IN_TRANSIT'
            AND transferred_quantity = requested_quantity
            AND dispatched_at IS NOT NULL
            AND received_at IS NULL
            AND destination_lot_id IS NULL
            AND source_quantity_after IS NOT NULL
            AND source_quantity_after = source_quantity_before - transferred_quantity
            AND source_version_after > source_version_before
            AND destination_quantity_before IS NULL
            AND destination_quantity_after IS NULL
            AND destination_version_after IS NULL
            AND completed_at IS NULL
            AND source_lot_status_at_dispatch IS NOT NULL)
        OR
        (status = 'RECEIVED'
            AND transferred_quantity = requested_quantity
            AND received_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND destination_lot_id IS NOT NULL
            AND destination_quantity_before IS NOT NULL
            AND destination_quantity_after IS NOT NULL
            AND destination_quantity_after = destination_quantity_before + transferred_quantity
            AND destination_version_after IS NOT NULL
            AND ((dispatched_at IS NULL AND source_lot_status_at_dispatch IS NULL)
                OR (dispatched_at IS NOT NULL AND source_lot_status_at_dispatch IS NOT NULL
                    AND source_version_after > source_version_before
                    AND source_quantity_after = source_quantity_before - transferred_quantity)))
    ),
    ADD CONSTRAINT ck_inventory_transfer_quantity CHECK (
        requested_quantity > 0 AND transferred_quantity >= 0
    ),
    ADD CONSTRAINT ck_inventory_transfer_versions CHECK (
        source_version_before >= 0
        AND (source_version_after IS NULL OR source_version_after > source_version_before)
        AND (destination_version_after IS NULL OR destination_version_after >= 0)
        AND version >= 0
    ),
    ADD CONSTRAINT ck_inventory_transfer_source_lot_status CHECK (
        source_lot_status_at_dispatch IS NULL
        OR source_lot_status_at_dispatch IN ('AVAILABLE','BLOCKED','QUARANTINED','HOLD')
    );

CREATE TABLE warehouse.inventory_transfer_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    transfer_version BIGINT NOT NULL,
    actor_membership_id UUID NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_transfer_history_version
        UNIQUE (tenant_id, workspace_id, transfer_id, transfer_version),
    CONSTRAINT fk_inventory_transfer_history_scope
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_transfer_history_actor
        FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_inventory_transfer_history_transfer
        FOREIGN KEY (tenant_id, workspace_id, transfer_id)
        REFERENCES warehouse.inventory_transfer (tenant_id, workspace_id, id),
    CONSTRAINT ck_inventory_transfer_history_transition CHECK (
        (from_status IS NULL AND to_status = 'REQUESTED' AND transfer_version = 0)
        OR (from_status = 'REQUESTED' AND to_status = 'IN_TRANSIT' AND transfer_version = 1)
        OR (from_status = 'IN_TRANSIT' AND to_status = 'RECEIVED' AND transfer_version = 2)
    )
);

CREATE INDEX ix_inventory_transfer_history_time
    ON warehouse.inventory_transfer_history (tenant_id, workspace_id, transfer_id, occurred_at, id);

CREATE OR REPLACE FUNCTION warehouse.guard_inventory_transfer_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'REQUESTED' THEN
            RAISE EXCEPTION 'warehouse inventory transfers must begin as REQUESTED';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'warehouse inventory transfers cannot be deleted';
    END IF;

    IF OLD.status = 'REQUESTED' AND NEW.status = 'IN_TRANSIT' THEN
        IF NEW.version <> OLD.version + 1
            OR NEW.transferred_quantity <> NEW.requested_quantity
            OR NEW.dispatched_at IS NULL
            OR NEW.source_quantity_after IS NULL
            OR NEW.source_version_after IS NULL
            OR NEW.source_lot_status_at_dispatch IS NULL
            OR NEW.received_at IS NOT NULL
            OR NEW.destination_lot_id IS NOT NULL THEN
            RAISE EXCEPTION 'invalid warehouse transfer dispatch transition';
        END IF;
        IF (to_jsonb(NEW) - ARRAY['status','version','transferred_quantity','source_quantity_before',
                'source_quantity_after','source_version_after','dispatched_at','source_lot_status_at_dispatch'])
            IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['status','version','transferred_quantity','source_quantity_before',
                'source_quantity_after','source_version_after','dispatched_at','source_lot_status_at_dispatch']) THEN
            RAISE EXCEPTION 'warehouse transfer request facts are immutable';
        END IF;
    ELSIF OLD.status = 'IN_TRANSIT' AND NEW.status = 'RECEIVED' THEN
        IF NEW.version <> OLD.version + 1
            OR NEW.transferred_quantity <> OLD.transferred_quantity
            OR NEW.source_version_after <> OLD.source_version_after
            OR NEW.source_quantity_before <> OLD.source_quantity_before
            OR NEW.source_quantity_after <> OLD.source_quantity_after
            OR NEW.source_lot_status_at_dispatch <> OLD.source_lot_status_at_dispatch
            OR NEW.dispatched_at <> OLD.dispatched_at
            OR NEW.received_at IS NULL
            OR NEW.completed_at IS NULL
            OR NEW.destination_lot_id IS NULL
            OR NEW.destination_quantity_before IS NULL
            OR NEW.destination_quantity_after IS NULL
            OR NEW.destination_version_after IS NULL THEN
            RAISE EXCEPTION 'invalid warehouse transfer receipt transition';
        END IF;
        IF (to_jsonb(NEW) - ARRAY['status','version','destination_lot_id','destination_quantity_before',
                'destination_quantity_after','destination_version_after','received_at','completed_at'])
            IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['status','version','destination_lot_id','destination_quantity_before',
                'destination_quantity_after','destination_version_after','received_at','completed_at']) THEN
            RAISE EXCEPTION 'warehouse transfer dispatch facts are immutable';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid warehouse transfer state transition';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER warehouse_inventory_transfer_lifecycle_guard
    BEFORE INSERT OR UPDATE OR DELETE ON warehouse.inventory_transfer
    FOR EACH ROW EXECUTE FUNCTION warehouse.guard_inventory_transfer_lifecycle();

CREATE TRIGGER warehouse_inventory_transfer_history_append_only
    BEFORE UPDATE OR DELETE ON warehouse.inventory_transfer_history
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE warehouse.inventory_transfer_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_transfer_history FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_transfer_history_tenant_workspace_scope
    ON warehouse.inventory_transfer_history
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON warehouse.inventory_transfer TO nexa_runtime;
        GRANT SELECT, INSERT ON warehouse.inventory_transfer_history TO nexa_runtime;
    END IF;
END;
$$;
