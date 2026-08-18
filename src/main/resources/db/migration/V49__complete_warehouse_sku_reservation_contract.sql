-- Canonical Warehouse identity is the sellable SKU. Legacy catalog_item_id remains
-- only as a compatibility projection for historical rows and old read clients.
ALTER TABLE warehouse.inventory_reservation_line
    ADD COLUMN IF NOT EXISTS sku_id UUID;

ALTER TABLE warehouse.inventory_reservation_line
    ADD CONSTRAINT fk_reservation_line_sku
        FOREIGN KEY (sku_id) REFERENCES catalog_management.sellable_sku(id);

UPDATE warehouse.inventory_reservation_line l
SET sku_id = s.id
FROM warehouse.inventory_reservation r
     , catalog_management.sellable_sku s
WHERE l.reservation_id = r.id
  AND l.sku_id IS NULL;

CREATE INDEX IF NOT EXISTS ix_inventory_lot_sku_fefo
    ON warehouse.inventory_lot (tenant_id, workspace_id, sku_id, status, expiration_date, received_at, id);
CREATE INDEX IF NOT EXISTS ix_inventory_lot_sku_available
    ON warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, sku_id, reserved_quantity, stock_quantity);
CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_lot_scope_sku_batch
    ON warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, sku_id, batch_number)
    WHERE sku_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_reservation_line_sku
    ON warehouse.inventory_reservation_line (reservation_id, sku_id);
