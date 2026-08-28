-- Indexes for the existing Catalog SKU and Inventory Lot identifier fields.
-- No identifier table is introduced; ownership remains BC-03/BC-05.
CREATE INDEX ix_sellable_sku_gtin_resolution
    ON catalog_management.sellable_sku (tenant_id, workspace_id, gtin, id)
    WHERE status = 'ACTIVE' AND visible;

CREATE INDEX ix_inventory_lot_batch_resolution
    ON warehouse.inventory_lot (tenant_id, workspace_id, batch_number, id);
