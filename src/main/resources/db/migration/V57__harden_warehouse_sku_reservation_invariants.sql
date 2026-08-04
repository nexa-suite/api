-- Warehouse identity is the tenant/workspace-scoped sellable SKU.  V49 left
-- reservation-line backfill unconstrained; repair that historical projection
-- from the sales order first and fail the migration if canonical identity is
-- still unavailable.
-- The stock movement ledger is append-only at runtime.  This one-time
-- historical SKU projection repair must run before restoring that trigger;
-- no application command can mutate the ledger after this migration.
DROP TRIGGER IF EXISTS warehouse_stock_movement_append_only ON warehouse.stock_movement;

UPDATE warehouse.inventory_reservation_line line
SET sku_id = order_line.sku_id
FROM warehouse.inventory_reservation reservation,
     sales.sales_order order_header,
     sales.sales_order_line order_line
WHERE line.reservation_id = reservation.id
  AND order_header.tenant_id = reservation.tenant_id
  AND order_header.workspace_id = reservation.workspace_id
  AND order_header.id = reservation.sales_order_id
  AND order_line.sales_order_id = order_header.id
  AND order_line.catalog_item_id = line.catalog_item_id
  AND order_line.sku_id IS NOT NULL
  AND line.sku_id IS DISTINCT FROM order_line.sku_id;

UPDATE warehouse.inventory_reservation_line line
SET sku_id = sku.id
FROM warehouse.inventory_reservation reservation,
     catalog_management.sellable_sku sku
WHERE line.reservation_id = reservation.id
  AND sku.tenant_id = reservation.tenant_id
  AND sku.workspace_id = reservation.workspace_id
  AND sku.legacy_catalog_item_id = line.catalog_item_id
  AND line.sku_id IS DISTINCT FROM sku.id;

UPDATE warehouse.inventory_lot lot
SET sku_id = sku.id
FROM catalog_management.sellable_sku sku
WHERE sku.tenant_id = lot.tenant_id
  AND sku.workspace_id = lot.workspace_id
  AND sku.legacy_catalog_item_id = lot.catalog_item_id
  AND lot.sku_id IS DISTINCT FROM sku.id;

UPDATE warehouse.stock_movement movement
SET sku_id = sku.id
FROM catalog_management.sellable_sku sku
WHERE sku.tenant_id = movement.tenant_id
  AND sku.workspace_id = movement.workspace_id
  AND sku.legacy_catalog_item_id = movement.catalog_item_id
  AND movement.sku_id IS DISTINCT FROM sku.id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM warehouse.inventory_lot WHERE sku_id IS NULL)
       OR EXISTS (SELECT 1 FROM warehouse.stock_movement WHERE sku_id IS NULL)
       OR EXISTS (SELECT 1 FROM warehouse.inventory_reservation_line WHERE sku_id IS NULL) THEN
        RAISE EXCEPTION 'Warehouse canonical SKU backfill is incomplete';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation_line line
        JOIN warehouse.inventory_reservation reservation ON reservation.id = line.reservation_id
        LEFT JOIN catalog_management.sellable_sku sku
          ON sku.id = line.sku_id
         AND sku.tenant_id = reservation.tenant_id
         AND sku.workspace_id = reservation.workspace_id
        WHERE sku.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Reservation line SKU scope is inconsistent';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation_allocation allocation
        JOIN warehouse.inventory_reservation_line line ON line.id = allocation.reservation_line_id
        JOIN warehouse.inventory_reservation reservation ON reservation.id = line.reservation_id
        LEFT JOIN warehouse.inventory_lot lot
          ON lot.id = allocation.lot_id
         AND lot.tenant_id = reservation.tenant_id
         AND lot.workspace_id = reservation.workspace_id
         AND lot.sku_id = line.sku_id
         AND lot.unit = allocation.unit
        WHERE lot.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Reservation allocation scope is inconsistent';
    END IF;
END $$;

ALTER TABLE warehouse.inventory_lot
    ALTER COLUMN sku_id SET NOT NULL;
ALTER TABLE warehouse.stock_movement
    ALTER COLUMN sku_id SET NOT NULL;
ALTER TABLE warehouse.inventory_reservation_line
    ALTER COLUMN sku_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_inventory_lot_scope_sku_status_fefo
    ON warehouse.inventory_lot
        (tenant_id, workspace_id, sku_id, warehouse_id, status, expiration_date, received_at, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservation_line_scope_sku
    ON warehouse.inventory_reservation_line (reservation_id, sku_id);

CREATE TRIGGER warehouse_stock_movement_append_only
    BEFORE UPDATE OR DELETE ON warehouse.stock_movement
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

-- The historical FK protects SKU existence but not tenant/workspace identity.
-- These triggers keep the internal split tables scoped without widening their
-- persisted contract with duplicated tenant/workspace columns.
CREATE OR REPLACE FUNCTION warehouse.validate_reservation_line_sku_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation reservation
        JOIN catalog_management.sellable_sku sku
          ON sku.id = NEW.sku_id
         AND sku.tenant_id = reservation.tenant_id
         AND sku.workspace_id = reservation.workspace_id
        WHERE reservation.id = NEW.reservation_id
    ) THEN
        RAISE EXCEPTION 'Reservation line SKU is outside the reservation tenant/workspace';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS reservation_line_sku_scope ON warehouse.inventory_reservation_line;
CREATE TRIGGER reservation_line_sku_scope
    BEFORE INSERT OR UPDATE OF reservation_id, sku_id
    ON warehouse.inventory_reservation_line
    FOR EACH ROW
    EXECUTE FUNCTION warehouse.validate_reservation_line_sku_scope();

CREATE OR REPLACE FUNCTION warehouse.validate_reservation_allocation_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation_line line
        JOIN warehouse.inventory_reservation reservation ON reservation.id = line.reservation_id
        JOIN warehouse.inventory_lot lot ON lot.id = NEW.lot_id
        WHERE line.id = NEW.reservation_line_id
          AND reservation.tenant_id = lot.tenant_id
          AND reservation.workspace_id = lot.workspace_id
          AND line.sku_id = lot.sku_id
          AND NEW.unit = line.unit
          AND NEW.unit = lot.unit
    ) THEN
        RAISE EXCEPTION 'Reservation allocation crosses SKU, unit or tenant/workspace boundary';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS reservation_allocation_scope ON warehouse.inventory_reservation_allocation;
CREATE TRIGGER reservation_allocation_scope
    BEFORE INSERT OR UPDATE OF reservation_line_id, lot_id, unit
    ON warehouse.inventory_reservation_allocation
    FOR EACH ROW
    EXECUTE FUNCTION warehouse.validate_reservation_allocation_scope();
