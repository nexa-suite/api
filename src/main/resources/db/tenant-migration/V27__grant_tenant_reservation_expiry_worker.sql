DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_warehouse_reservation_expiry_worker') THEN
        RAISE EXCEPTION 'nexa_warehouse_reservation_expiry_worker role must be provisioned before Tenant migration V27';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA nexa_platform, warehouse, integration
    TO nexa_warehouse_reservation_expiry_worker;

GRANT SELECT (singleton, tenant_id, database_identity)
    ON TABLE nexa_platform.tenant_business_database_identity
    TO nexa_warehouse_reservation_expiry_worker;
GRANT SELECT (tenant_id, workspace_id)
    ON TABLE nexa_platform.tenant_workspace_scope_anchor
    TO nexa_warehouse_reservation_expiry_worker;

GRANT SELECT (id, tenant_id, workspace_id, sales_order_id, order_number, client_account_id,
              status, created_at, reserved_at, expires_at, version)
    ON TABLE warehouse.inventory_reservation
    TO nexa_warehouse_reservation_expiry_worker;
GRANT UPDATE (status, updated_at, version)
    ON TABLE warehouse.inventory_reservation
    TO nexa_warehouse_reservation_expiry_worker;

GRANT SELECT (id, reservation_id)
    ON TABLE warehouse.inventory_reservation_line
    TO nexa_warehouse_reservation_expiry_worker;
GRANT SELECT (reservation_line_id, lot_id, quantity, unit, expiration_date)
    ON TABLE warehouse.inventory_reservation_allocation
    TO nexa_warehouse_reservation_expiry_worker;

GRANT SELECT (id, tenant_id, workspace_id, warehouse_id, zone_id, catalog_item_id, sku_id,
              batch_number, expiration_date, received_at, production_date, stock_quantity,
              reserved_quantity, unit, status, version)
    ON TABLE warehouse.inventory_lot
    TO nexa_warehouse_reservation_expiry_worker;
GRANT UPDATE (reserved_quantity, version)
    ON TABLE warehouse.inventory_lot
    TO nexa_warehouse_reservation_expiry_worker;
GRANT SELECT (id, tenant_id, workspace_id, lot_id, affected_quantity, status, disposition)
    ON TABLE warehouse.inventory_temperature_evaluation
    TO nexa_warehouse_reservation_expiry_worker;
GRANT SELECT (tenant_id, workspace_id, temperature_evaluation_id, disposition, quantity)
    ON TABLE warehouse.inventory_lot_disposition
    TO nexa_warehouse_reservation_expiry_worker;

GRANT INSERT (id, tenant_id, workspace_id, warehouse_id, zone_id, lot_id, catalog_item_id,
              sku_id, movement_type, quantity, unit, quantity_before, quantity_after,
              reserved_before, reserved_after, reason, actor_membership_id, correlation_id, occurred_at)
    ON TABLE warehouse.stock_movement
    TO nexa_warehouse_reservation_expiry_worker;
GRANT INSERT (id, tenant_id, workspace_id, aggregate_id, event_type, occurred_at,
              actor_membership_id, correlation_id)
    ON TABLE warehouse.inventory_event
    TO nexa_warehouse_reservation_expiry_worker;

GRANT INSERT (event_id, tenant_id, workspace_id, client_account_id, aggregate_type,
              aggregate_id, event_type, public_status, audiences, occurred_at, expires_at)
    ON TABLE integration.change_event
    TO nexa_warehouse_reservation_expiry_worker;
GRANT USAGE ON SEQUENCE integration.change_event_sequence_seq
    TO nexa_warehouse_reservation_expiry_worker;
