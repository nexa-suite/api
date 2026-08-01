ALTER TABLE warehouse.command_idempotency
    ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE warehouse.inventory_reservation
    ADD CONSTRAINT uq_reservation_scope_id UNIQUE (tenant_id, workspace_id, id);

ALTER TABLE warehouse.inventory_lot
    ADD COLUMN IF NOT EXISTS temperature_value NUMERIC(9,4);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_lot_temperature_value'
          AND conrelid = 'warehouse.inventory_lot'::regclass
    ) THEN
        ALTER TABLE warehouse.inventory_lot
            ADD CONSTRAINT ck_lot_temperature_value
            CHECK (temperature_value IS NULL OR (temperature_value > -1000 AND temperature_value < 1000));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_command_idempotency_hash'
          AND conrelid = 'warehouse.command_idempotency'::regclass
    ) THEN
        ALTER TABLE warehouse.command_idempotency
            ADD CONSTRAINT ck_command_idempotency_hash
            CHECK (length(request_hash) = 0 OR request_hash ~ '^[0-9a-fA-F]{64}$');
    END IF;
END $$;

DROP INDEX IF EXISTS warehouse.uq_active_reservation_order;
CREATE UNIQUE INDEX uq_active_reservation_order
    ON warehouse.inventory_reservation (tenant_id, workspace_id, sales_order_id)
    WHERE status IN ('PENDING', 'RESERVED');

CREATE INDEX IF NOT EXISTS ix_reservation_retry_order
    ON warehouse.inventory_reservation (tenant_id, workspace_id, sales_order_id, status, updated_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_reservation_scope_expiry
    ON warehouse.inventory_reservation (tenant_id, workspace_id, status, expires_at, id);
CREATE INDEX IF NOT EXISTS ix_allocation_lot
    ON warehouse.inventory_reservation_allocation (lot_id, reservation_line_id);
CREATE INDEX IF NOT EXISTS ix_shortage_line
    ON warehouse.reservation_shortage (reservation_line_id, id);
CREATE INDEX IF NOT EXISTS ix_inventory_event_scope_time
    ON warehouse.inventory_event (tenant_id, workspace_id, occurred_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_lot_scope_status_fefo
    ON warehouse.inventory_lot (tenant_id, workspace_id, status, catalog_item_id, expiration_date, received_at, id);

DROP TRIGGER IF EXISTS warehouse_command_idempotency_append_only ON warehouse.command_idempotency;
CREATE TRIGGER warehouse_command_idempotency_append_only
    BEFORE UPDATE OR DELETE ON warehouse.command_idempotency
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
