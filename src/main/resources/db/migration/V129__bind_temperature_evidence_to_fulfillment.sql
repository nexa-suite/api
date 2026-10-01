ALTER TABLE logistics.temperature_evidence
    ADD COLUMN fulfillment_id UUID,
    ADD COLUMN fulfillment_version BIGINT;

ALTER TABLE logistics.temperature_evidence
    DROP CONSTRAINT ck_temperature_evidence_subject,
    ADD CONSTRAINT fk_temperature_evidence_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    ADD CONSTRAINT ck_temperature_evidence_fulfillment_version CHECK (fulfillment_version IS NULL OR fulfillment_version >= 0),
    ADD CONSTRAINT ck_temperature_evidence_subject CHECK (
        (subject_type = 'DELIVERY' AND delivery_id IS NOT NULL AND fulfillment_id IS NULL
            AND fulfillment_version IS NULL AND subject_id = delivery_id)
        OR (subject_type = 'FULFILLMENT' AND delivery_id IS NULL AND fulfillment_id IS NOT NULL
            AND fulfillment_version IS NOT NULL AND subject_id = fulfillment_id
            AND lot_id IS NOT NULL AND warehouse_id IS NOT NULL AND zone_id IS NOT NULL)
        OR (subject_type = 'LOT' AND delivery_id IS NULL AND fulfillment_id IS NULL
            AND fulfillment_version IS NULL AND lot_id IS NOT NULL
            AND warehouse_id IS NOT NULL AND zone_id IS NOT NULL AND subject_id = lot_id)
        OR (subject_type = 'WAREHOUSE' AND delivery_id IS NULL AND fulfillment_id IS NULL
            AND fulfillment_version IS NULL AND lot_id IS NULL
            AND warehouse_id IS NOT NULL AND zone_id IS NULL AND subject_id = warehouse_id)
    );

CREATE INDEX ix_temperature_evidence_fulfillment_current
    ON logistics.temperature_evidence (tenant_id, workspace_id, fulfillment_id, lot_id, fulfillment_version,
                                       recorded_at DESC, id DESC)
    WHERE subject_type = 'FULFILLMENT';
