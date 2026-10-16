-- Preserve unknown historical production dates; never infer manufacturing facts.
ALTER TABLE warehouse.inventory_lot ADD COLUMN production_date date;
ALTER TABLE warehouse.inventory_lot ADD CONSTRAINT ck_inventory_lot_production_chronology
    CHECK (production_date IS NULL OR (production_date < expiration_date AND production_date <= (received_at AT TIME ZONE 'UTC')::date));
