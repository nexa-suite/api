CREATE SCHEMA warehouse;

CREATE TABLE warehouse.warehouse (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, workspace_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL, name VARCHAR(160) NOT NULL, address TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_warehouse_scope_id UNIQUE (tenant_id, workspace_id, id), CONSTRAINT uq_warehouse_scope_code UNIQUE (tenant_id, workspace_id, code),
    CONSTRAINT fk_warehouse_scope FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_warehouse_status CHECK (status IN ('ACTIVE','SUSPENDED'))
);

CREATE TABLE warehouse.storage_zone (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, workspace_id UUID NOT NULL, warehouse_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL, name VARCHAR(160) NOT NULL, zone_type VARCHAR(16) NOT NULL,
    temperature_min NUMERIC(9,4), temperature_max NUMERIC(9,4), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_zone_scope_id UNIQUE (tenant_id, workspace_id, warehouse_id, id), CONSTRAINT uq_zone_scope_code UNIQUE (tenant_id, workspace_id, warehouse_id, code),
    CONSTRAINT fk_zone_scope_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id) REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT ck_zone_type CHECK (zone_type IN ('AMBIENT','CHILLED','FROZEN','QUARANTINE')),
    CONSTRAINT ck_zone_status CHECK (status IN ('ACTIVE','SUSPENDED')),
    CONSTRAINT ck_zone_temperature CHECK (temperature_min IS NULL OR temperature_max IS NULL OR temperature_min <= temperature_max)
);

CREATE TABLE warehouse.inventory_lot (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, workspace_id UUID NOT NULL, warehouse_id UUID NOT NULL, zone_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL, batch_number VARCHAR(80) NOT NULL, expiration_date DATE NOT NULL, received_at TIMESTAMPTZ NOT NULL,
    stock_quantity NUMERIC(19,4) NOT NULL, reserved_quantity NUMERIC(19,4) NOT NULL DEFAULT 0, unit VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE', temperature_range_snapshot VARCHAR(160), version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_lot_scope_id UNIQUE (tenant_id, workspace_id, warehouse_id, zone_id, id), CONSTRAINT uq_lot_scope_batch UNIQUE (tenant_id, workspace_id, warehouse_id, catalog_item_id, batch_number),
    CONSTRAINT fk_lot_scope_zone FOREIGN KEY (tenant_id, workspace_id, warehouse_id, zone_id) REFERENCES warehouse.storage_zone (tenant_id, workspace_id, warehouse_id, id),
    CONSTRAINT ck_lot_quantities CHECK (stock_quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity <= stock_quantity),
    CONSTRAINT ck_lot_status CHECK (status IN ('AVAILABLE','BLOCKED','QUARANTINED','EXPIRED','DEPLETED'))
);

CREATE TABLE warehouse.stock_movement (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, workspace_id UUID NOT NULL, warehouse_id UUID NOT NULL, zone_id UUID NOT NULL, lot_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL, movement_type VARCHAR(32) NOT NULL, quantity NUMERIC(19,4) NOT NULL, unit VARCHAR(32) NOT NULL,
    quantity_before NUMERIC(19,4) NOT NULL, quantity_after NUMERIC(19,4) NOT NULL, reserved_before NUMERIC(19,4) NOT NULL, reserved_after NUMERIC(19,4) NOT NULL,
    reason VARCHAR(2000), actor_membership_id UUID NOT NULL, correlation_id VARCHAR(160) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_movement_lot FOREIGN KEY (tenant_id, workspace_id, warehouse_id, zone_id, lot_id) REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, zone_id, id),
    CONSTRAINT ck_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_movement_type CHECK (movement_type IN ('INBOUND_RECEIPT','ADJUSTMENT_IN','ADJUSTMENT_OUT','WASTE','RESERVATION','RESERVATION_RELEASE','RESERVATION_EXPIRATION','OUTBOUND_CONSUMPTION'))
);

CREATE TABLE warehouse.inventory_event (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, workspace_id UUID NOT NULL, aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, actor_membership_id UUID NOT NULL, correlation_id VARCHAR(160) NOT NULL
);

CREATE INDEX ix_warehouse_scope_status ON warehouse.warehouse (tenant_id, workspace_id, status, code, id);
CREATE INDEX ix_zone_scope_status ON warehouse.storage_zone (tenant_id, workspace_id, warehouse_id, status, code, id);
CREATE INDEX ix_lot_fefo ON warehouse.inventory_lot (tenant_id, workspace_id, catalog_item_id, status, expiration_date, received_at, id);
CREATE INDEX ix_lot_available ON warehouse.inventory_lot (tenant_id, workspace_id, catalog_item_id, reserved_quantity, stock_quantity);
CREATE INDEX ix_movement_scope_time ON warehouse.stock_movement (tenant_id, workspace_id, occurred_at DESC, id);

CREATE TRIGGER warehouse_stock_movement_append_only BEFORE UPDATE OR DELETE ON warehouse.stock_movement FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER warehouse_inventory_event_append_only BEFORE UPDATE OR DELETE ON warehouse.inventory_event FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
