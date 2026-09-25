CREATE TABLE warehouse.stock_temperature_evidence (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    warehouse_id UUID NOT NULL,
    zone_id UUID,
    lot_id UUID,
    value NUMERIC NOT NULL,
    unit VARCHAR(16) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    actor_membership_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_stock_temperature_evidence_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_stock_temperature_evidence_actor_key UNIQUE
        (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_stock_temperature_evidence_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_stock_temperature_evidence_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT fk_stock_temperature_evidence_lot FOREIGN KEY
        (tenant_id, workspace_id, warehouse_id, zone_id, lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, zone_id, id),
    CONSTRAINT fk_stock_temperature_evidence_actor FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_stock_temperature_evidence_subject CHECK (
        (subject_type = 'LOT' AND zone_id IS NOT NULL AND lot_id IS NOT NULL)
        OR (subject_type = 'WAREHOUSE' AND zone_id IS NULL AND lot_id IS NULL)
    ),
    CONSTRAINT ck_stock_temperature_evidence_unit CHECK (unit IN ('CELSIUS', 'FAHRENHEIT')),
    CONSTRAINT ck_stock_temperature_evidence_status CHECK (status = 'PENDING'),
    CONSTRAINT ck_stock_temperature_evidence_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_stock_temperature_evidence_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160)
);

CREATE INDEX ix_stock_temperature_evidence_subject_time
    ON warehouse.stock_temperature_evidence
       (tenant_id, workspace_id, subject_type, warehouse_id, lot_id, observed_at DESC, id);

CREATE TRIGGER stock_temperature_evidence_append_only
    BEFORE UPDATE OR DELETE ON warehouse.stock_temperature_evidence
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE warehouse.stock_temperature_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.stock_temperature_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_temperature_evidence_tenant_workspace_scope
    ON warehouse.stock_temperature_evidence
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT USAGE ON SCHEMA warehouse TO nexa_runtime;
        GRANT SELECT, INSERT ON warehouse.stock_temperature_evidence TO nexa_runtime;
    END IF;
END;
$$;
