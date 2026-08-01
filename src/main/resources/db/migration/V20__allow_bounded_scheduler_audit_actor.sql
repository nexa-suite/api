ALTER TABLE warehouse.stock_movement
    ALTER COLUMN actor_membership_id DROP NOT NULL;

ALTER TABLE warehouse.inventory_event
    ALTER COLUMN actor_membership_id DROP NOT NULL;
