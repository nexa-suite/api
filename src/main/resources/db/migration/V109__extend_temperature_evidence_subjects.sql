ALTER TABLE logistics.temperature_evidence
    ADD COLUMN subject_type VARCHAR(16),
    ADD COLUMN subject_id UUID,
    ADD COLUMN warehouse_id UUID,
    ADD COLUMN zone_id UUID;

UPDATE logistics.temperature_evidence
SET subject_type = 'DELIVERY',
    subject_id = delivery_id;

ALTER TABLE logistics.temperature_evidence
    ALTER COLUMN delivery_id DROP NOT NULL,
    ALTER COLUMN subject_type SET NOT NULL,
    ALTER COLUMN subject_id SET NOT NULL,
    ALTER COLUMN value TYPE NUMERIC,
    ALTER COLUMN temperature_celsius TYPE NUMERIC,
    ADD CONSTRAINT fk_temperature_evidence_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_temperature_evidence_lot FOREIGN KEY (tenant_id, workspace_id, warehouse_id, zone_id, lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, warehouse_id, zone_id, id),
    ADD CONSTRAINT ck_temperature_evidence_subject CHECK (
        (subject_type = 'DELIVERY' AND delivery_id IS NOT NULL AND subject_id = delivery_id)
        OR (subject_type = 'LOT' AND delivery_id IS NULL AND lot_id IS NOT NULL
            AND warehouse_id IS NOT NULL AND zone_id IS NOT NULL AND subject_id = lot_id)
        OR (subject_type = 'WAREHOUSE' AND delivery_id IS NULL AND lot_id IS NULL
            AND warehouse_id IS NOT NULL AND zone_id IS NULL AND subject_id = warehouse_id)
    );

CREATE INDEX ix_temperature_evidence_subject
    ON logistics.temperature_evidence (tenant_id, workspace_id, subject_type, subject_id, recorded_at DESC);
