CREATE TABLE warehouse.inventory_transfer_receipt_observation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    transfer_version BIGINT NOT NULL,
    source_warehouse_id UUID NOT NULL,
    source_zone_id UUID NOT NULL,
    source_lot_id UUID NOT NULL,
    destination_warehouse_id UUID NOT NULL,
    destination_zone_id UUID NOT NULL,
    expected_batch_number VARCHAR(80) NOT NULL,
    expected_expiration_date DATE NOT NULL,
    expected_quantity NUMERIC(19,4) NOT NULL,
    expected_unit VARCHAR(32) NOT NULL,
    observed_batch_number VARCHAR(80) NOT NULL,
    observed_expiration_date DATE,
    observed_quantity NUMERIC(19,4) NOT NULL,
    observed_unit VARCHAR(32) NOT NULL,
    actor_membership_id UUID NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_transfer_receipt_observation_scope_id
        UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_inventory_transfer_receipt_observation_scope
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inventory_transfer_receipt_observation_transfer
        FOREIGN KEY (tenant_id, workspace_id, transfer_id)
        REFERENCES warehouse.inventory_transfer (tenant_id, workspace_id, id),
    CONSTRAINT fk_inventory_transfer_receipt_observation_actor
        FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_inventory_transfer_receipt_observation_version
        CHECK (transfer_version >= 0),
    CONSTRAINT ck_inventory_transfer_receipt_observation_quantities
        CHECK (expected_quantity > 0 AND observed_quantity >= 0),
    CONSTRAINT ck_inventory_transfer_receipt_observation_units
        CHECK (length(btrim(expected_unit)) BETWEEN 1 AND 32
            AND upper(btrim(expected_unit)) = upper(btrim(observed_unit))),
    CONSTRAINT ck_inventory_transfer_receipt_observation_batches
        CHECK (length(btrim(expected_batch_number)) BETWEEN 1 AND 80
            AND length(btrim(observed_batch_number)) BETWEEN 1 AND 80),
    CONSTRAINT ck_inventory_transfer_receipt_observation_is_difference
        CHECK (expected_batch_number <> observed_batch_number
            OR (observed_expiration_date IS NOT NULL
                AND expected_expiration_date IS DISTINCT FROM observed_expiration_date)
            OR expected_quantity <> observed_quantity)
);

CREATE INDEX ix_inventory_transfer_receipt_observation_current
    ON warehouse.inventory_transfer_receipt_observation
        (tenant_id, workspace_id, transfer_id, recorded_at DESC, id DESC);

CREATE TRIGGER warehouse_inventory_transfer_receipt_observation_append_only
    BEFORE UPDATE OR DELETE ON warehouse.inventory_transfer_receipt_observation
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE warehouse.inventory_transfer_receipt_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_transfer_receipt_observation FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_transfer_receipt_observation_tenant_workspace_scope
    ON warehouse.inventory_transfer_receipt_observation
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON warehouse.inventory_transfer_receipt_observation TO nexa_runtime;
    END IF;
END;
$$;
