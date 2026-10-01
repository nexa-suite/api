CREATE TABLE warehouse.inventory_cycle_count (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    lot_version BIGINT NOT NULL,
    expected_quantity NUMERIC(19,4) NOT NULL,
    observed_quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    actor_membership_id UUID NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_cycle_count_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_inventory_cycle_count_scope_lot UNIQUE (tenant_id, workspace_id, id, lot_id),
    CONSTRAINT fk_inventory_cycle_count_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_cycle_count_lot FOREIGN KEY (tenant_id, workspace_id, warehouse_id, zone_id, lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, zone_id, id),
    CONSTRAINT fk_inventory_cycle_count_actor FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_inventory_cycle_count_version CHECK (lot_version >= 0),
    CONSTRAINT ck_inventory_cycle_count_quantities CHECK (expected_quantity >= 0 AND observed_quantity >= 0),
    CONSTRAINT ck_inventory_cycle_count_unit CHECK (length(btrim(unit)) BETWEEN 1 AND 32
        AND upper(btrim(unit)) = unit),
    CONSTRAINT ck_inventory_cycle_count_status CHECK (status IN ('RECORDED', 'REQUESTED')),
    CONSTRAINT ck_inventory_cycle_count_request_status CHECK (
        (status = 'RECORDED' AND expected_quantity = observed_quantity)
        OR (status = 'REQUESTED' AND expected_quantity <> observed_quantity)
    )
);

CREATE INDEX ix_inventory_cycle_count_lot_time
    ON warehouse.inventory_cycle_count (tenant_id, workspace_id, lot_id, recorded_at DESC, id DESC);

CREATE TRIGGER warehouse_inventory_cycle_count_append_only
    BEFORE UPDATE OR DELETE ON warehouse.inventory_cycle_count
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE warehouse.inventory_cycle_count_correction (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    cycle_count_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    lot_version_before BIGINT NOT NULL,
    lot_version_after BIGINT NOT NULL,
    quantity_before NUMERIC(19,4) NOT NULL,
    quantity_after NUMERIC(19,4) NOT NULL,
    quantity_delta NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    movement_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_cycle_count_correction_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_inventory_cycle_count_correction_count UNIQUE (tenant_id, workspace_id, cycle_count_id),
    CONSTRAINT fk_inventory_cycle_count_correction_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_cycle_count_correction_count FOREIGN KEY (tenant_id, workspace_id, cycle_count_id, lot_id)
        REFERENCES warehouse.inventory_cycle_count (tenant_id, workspace_id, id, lot_id),
    CONSTRAINT fk_inventory_cycle_count_correction_lot FOREIGN KEY (tenant_id, workspace_id, warehouse_id, zone_id, lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, zone_id, id),
    CONSTRAINT fk_inventory_cycle_count_correction_movement FOREIGN KEY (movement_id)
        REFERENCES warehouse.stock_movement (id),
    CONSTRAINT fk_inventory_cycle_count_correction_actor FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_inventory_cycle_count_correction_versions CHECK (
        lot_version_before >= 0 AND lot_version_after = lot_version_before + 1),
    CONSTRAINT ck_inventory_cycle_count_correction_quantities CHECK (
        quantity_before >= 0 AND quantity_after >= 0
        AND quantity_delta = quantity_after - quantity_before AND quantity_delta <> 0),
    CONSTRAINT ck_inventory_cycle_count_correction_unit CHECK (length(btrim(unit)) BETWEEN 1 AND 32
        AND upper(btrim(unit)) = unit)
);

CREATE INDEX ix_inventory_cycle_count_correction_lot_time
    ON warehouse.inventory_cycle_count_correction (tenant_id, workspace_id, lot_id, recorded_at DESC, id DESC);

CREATE TRIGGER warehouse_inventory_cycle_count_correction_append_only
    BEFORE UPDATE OR DELETE ON warehouse.inventory_cycle_count_correction
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE warehouse.inventory_cycle_count ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_cycle_count FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_cycle_count_tenant_workspace_scope
    ON warehouse.inventory_cycle_count
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE warehouse.inventory_cycle_count_correction ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_cycle_count_correction FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_cycle_count_correction_tenant_workspace_scope
    ON warehouse.inventory_cycle_count_correction
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON warehouse.inventory_cycle_count TO nexa_runtime;
        GRANT SELECT, INSERT ON warehouse.inventory_cycle_count_correction TO nexa_runtime;
    END IF;
END;
$$;
