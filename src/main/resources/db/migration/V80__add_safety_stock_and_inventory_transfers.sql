-- V79 is owned by delivery attempts/continuations in this checkout.
-- Inventory Availability therefore uses V80: tenant/workspace-scoped safety
-- stock and immutable transfer evidence. Existing migrations remain unchanged.

CREATE TABLE warehouse.safety_stock_policy (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    actor_membership_id UUID NOT NULL,
    CONSTRAINT uq_safety_stock_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_safety_stock_warehouse_sku UNIQUE (tenant_id, workspace_id, warehouse_id, sku_id),
    CONSTRAINT fk_safety_stock_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_safety_stock_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT fk_safety_stock_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT ck_safety_stock_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_safety_stock_unit CHECK (length(btrim(unit)) BETWEEN 1 AND 32),
    CONSTRAINT ck_safety_stock_version CHECK (version >= 0)
);

CREATE TABLE warehouse.inventory_transfer (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    source_warehouse_id UUID NOT NULL,
    source_zone_id UUID NOT NULL,
    source_lot_id UUID NOT NULL,
    destination_warehouse_id UUID NOT NULL,
    destination_zone_id UUID NOT NULL,
    destination_lot_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    batch_number VARCHAR(80) NOT NULL,
    expiration_date DATE NOT NULL,
    requested_quantity NUMERIC(19,4) NOT NULL,
    transferred_quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    source_quantity_before NUMERIC(19,4) NOT NULL,
    source_quantity_after NUMERIC(19,4) NOT NULL,
    destination_quantity_before NUMERIC(19,4) NOT NULL,
    destination_quantity_after NUMERIC(19,4) NOT NULL,
    source_version_before BIGINT NOT NULL,
    source_version_after BIGINT NOT NULL,
    destination_version_after BIGINT NOT NULL,
    actor_membership_id UUID NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_transfer_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_inventory_transfer_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_transfer_source_warehouse FOREIGN KEY (tenant_id, workspace_id, source_warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT fk_inventory_transfer_source_zone FOREIGN KEY (tenant_id, workspace_id, source_warehouse_id, source_zone_id)
        REFERENCES warehouse.storage_zone (tenant_id, workspace_id, warehouse_id, id),
    CONSTRAINT fk_inventory_transfer_source_lot FOREIGN KEY (tenant_id, workspace_id, source_lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, id),
    CONSTRAINT fk_inventory_transfer_destination_warehouse FOREIGN KEY (tenant_id, workspace_id, destination_warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT fk_inventory_transfer_destination_zone FOREIGN KEY (tenant_id, workspace_id, destination_warehouse_id, destination_zone_id)
        REFERENCES warehouse.storage_zone (tenant_id, workspace_id, warehouse_id, id),
    CONSTRAINT fk_inventory_transfer_destination_lot FOREIGN KEY (tenant_id, workspace_id, destination_lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, id),
    CONSTRAINT fk_inventory_transfer_sku FOREIGN KEY (tenant_id, workspace_id, sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT ck_inventory_transfer_quantity CHECK (requested_quantity > 0 AND transferred_quantity = requested_quantity),
    CONSTRAINT ck_inventory_transfer_mode CHECK (mode IN ('FULL','PARTIAL')),
    CONSTRAINT ck_inventory_transfer_status CHECK (status IN ('COMPLETED')),
    CONSTRAINT ck_inventory_transfer_versions CHECK (
        source_version_before >= 0 AND source_version_after > source_version_before AND destination_version_after >= 0
    )
);

ALTER TABLE warehouse.stock_movement
    DROP CONSTRAINT IF EXISTS ck_movement_type;
ALTER TABLE warehouse.stock_movement
    ADD CONSTRAINT ck_movement_type CHECK (movement_type IN (
        'INBOUND_RECEIPT','ADJUSTMENT_IN','ADJUSTMENT_OUT','WASTE','RESERVATION',
        'RESERVATION_RELEASE','RESERVATION_EXPIRATION','OUTBOUND_CONSUMPTION',
        'TRANSFER_OUT','TRANSFER_IN'
    ));

CREATE INDEX ix_safety_stock_scope_warehouse_sku
    ON warehouse.safety_stock_policy (tenant_id, workspace_id, warehouse_id, sku_id, version);
CREATE INDEX ix_inventory_transfer_scope_time
    ON warehouse.inventory_transfer (tenant_id, workspace_id, created_at DESC, id);
CREATE INDEX ix_inventory_transfer_source_lot
    ON warehouse.inventory_transfer (tenant_id, workspace_id, source_lot_id, created_at DESC, id);
CREATE INDEX ix_inventory_transfer_destination
    ON warehouse.inventory_transfer (tenant_id, workspace_id, destination_warehouse_id, destination_zone_id, created_at DESC, id);

CREATE TRIGGER warehouse_inventory_transfer_append_only
    BEFORE UPDATE OR DELETE ON warehouse.inventory_transfer
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE warehouse.safety_stock_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.safety_stock_policy FORCE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_transfer FORCE ROW LEVEL SECURITY;

CREATE POLICY safety_stock_policy_tenant_workspace_scope
    ON warehouse.safety_stock_policy
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

CREATE POLICY inventory_transfer_tenant_workspace_scope
    ON warehouse.inventory_transfer
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON warehouse.safety_stock_policy TO nexa_runtime;
        GRANT SELECT, INSERT ON warehouse.inventory_transfer TO nexa_runtime;
    END IF;
END;
$$;
