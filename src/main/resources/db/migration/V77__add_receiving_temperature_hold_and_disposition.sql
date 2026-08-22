ALTER TABLE warehouse.inventory_lot
    DROP CONSTRAINT IF EXISTS ck_lot_status;
ALTER TABLE warehouse.inventory_lot
    ADD CONSTRAINT ck_lot_status CHECK (status IN ('AVAILABLE','BLOCKED','QUARANTINED','HOLD','EXPIRED','DEPLETED'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_lot_tenant_workspace_id
    ON warehouse.inventory_lot (tenant_id, workspace_id, id);

CREATE TABLE warehouse.inventory_temperature_evaluation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    received_value NUMERIC(9,4) NOT NULL,
    expected_min NUMERIC(9,4),
    expected_max NUMERIC(9,4),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    disposition VARCHAR(24) NOT NULL DEFAULT 'HOLD',
    resolution_reason VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT fk_inventory_temperature_evaluation_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_temperature_evaluation_lot FOREIGN KEY (tenant_id, workspace_id, lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, id),
    CONSTRAINT ck_inventory_temperature_evaluation_status CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT ck_inventory_temperature_evaluation_disposition CHECK (disposition IN ('RELEASE','HOLD','WASTE','RETURN_TO_SUPPLIER'))
);

CREATE TABLE warehouse.inventory_lot_disposition (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    lot_id UUID NOT NULL,
    disposition VARCHAR(24) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_inventory_lot_disposition_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_lot_disposition_lot FOREIGN KEY (tenant_id, workspace_id, lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, id),
    CONSTRAINT ck_inventory_lot_disposition_value CHECK (disposition IN ('RELEASE','HOLD','WASTE','RETURN_TO_SUPPLIER'))
);

CREATE INDEX ix_inventory_temperature_evaluation_scope ON warehouse.inventory_temperature_evaluation
    (tenant_id, workspace_id, status, created_at DESC, id);
CREATE INDEX ix_inventory_lot_disposition_lot ON warehouse.inventory_lot_disposition
    (tenant_id, workspace_id, lot_id, created_at DESC, id);

CREATE TRIGGER warehouse_lot_disposition_append_only
    BEFORE UPDATE OR DELETE ON warehouse.inventory_lot_disposition
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'warehouse.inventory_temperature_evaluation',
        'warehouse.inventory_lot_disposition'
    ] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('DROP POLICY IF EXISTS tenant_workspace_isolation ON %s', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_workspace_isolation ON %s USING (tenant_id = current_setting(''app.current_tenant_id'', true)::uuid AND workspace_id = current_setting(''app.current_workspace_id'', true)::uuid) WITH CHECK (tenant_id = current_setting(''app.current_tenant_id'', true)::uuid AND workspace_id = current_setting(''app.current_workspace_id'', true)::uuid)',
            table_name
        );
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %s TO nexa_runtime', table_name);
        END IF;
    END LOOP;
END $$;
