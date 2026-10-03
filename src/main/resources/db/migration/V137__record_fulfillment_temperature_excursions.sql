ALTER TABLE logistics.temperature_evidence
    ADD COLUMN expected_lot_version BIGINT,
    ADD COLUMN resulting_lot_version BIGINT,
    ADD COLUMN inventory_temperature_evaluation_id UUID,
    ADD COLUMN inventory_lot_status VARCHAR(16),
    ADD COLUMN affected_quantity NUMERIC(19,4),
    ADD CONSTRAINT ck_temperature_evidence_expected_lot_version
        CHECK (expected_lot_version IS NULL OR expected_lot_version >= 0),
    ADD CONSTRAINT ck_temperature_evidence_resulting_lot_version
        CHECK (resulting_lot_version IS NULL OR resulting_lot_version >= 0),
    ADD CONSTRAINT ck_temperature_evidence_affected_quantity
        CHECK (affected_quantity IS NULL OR affected_quantity > 0),
    ADD CONSTRAINT ck_temperature_evidence_inventory_lot_status
        CHECK (inventory_lot_status IS NULL OR inventory_lot_status = 'HOLD');

COMMENT ON COLUMN logistics.temperature_evidence.expected_lot_version IS
    'Lot version supplied by the client when recording fulfillment temperature evidence; null for legacy commands.';
COMMENT ON COLUMN logistics.temperature_evidence.resulting_lot_version IS
    'Lot version after the atomic BC-05 preventive hold; unchanged for an in-range reading.';
COMMENT ON COLUMN logistics.temperature_evidence.inventory_temperature_evaluation_id IS
    'Opaque identifier of the BC-05-owned excursion evaluation; there is intentionally no cross-context foreign key.';
COMMENT ON COLUMN logistics.temperature_evidence.inventory_lot_status IS
    'BC-05 lot status observed after the evidence command; current fulfillment excursion commands can only set HOLD.';
COMMENT ON COLUMN logistics.temperature_evidence.affected_quantity IS
    'Remaining allocated quantity affected by the preventive fulfillment temperature hold.';

ALTER TABLE warehouse.inventory_temperature_evaluation
    ALTER COLUMN received_value DROP NOT NULL,
    ADD COLUMN observed_value NUMERIC(9,4),
    ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'RECEIVING',
    ADD COLUMN source_subject_id UUID,
    ADD COLUMN source_subject_version BIGINT,
    ADD COLUMN temperature_evidence_id UUID,
    ADD COLUMN expected_lot_version BIGINT,
    ADD CONSTRAINT ck_inventory_temperature_evaluation_source_type
        CHECK (source_type IN ('RECEIVING','FULFILLMENT')),
    ADD CONSTRAINT ck_inventory_temperature_evaluation_source_version
        CHECK (source_subject_version IS NULL OR source_subject_version >= 0),
    ADD CONSTRAINT ck_inventory_temperature_evaluation_expected_lot_version
        CHECK (expected_lot_version IS NULL OR expected_lot_version >= 0),
    ADD CONSTRAINT ck_inventory_temperature_evaluation_fulfillment_source
        CHECK (source_type <> 'FULFILLMENT' OR
               (source_subject_id IS NOT NULL AND source_subject_version IS NOT NULL
                AND temperature_evidence_id IS NOT NULL AND expected_lot_version IS NOT NULL
                AND evidence_object_id IS NOT NULL AND observed_value IS NOT NULL));

UPDATE warehouse.inventory_temperature_evaluation
SET observed_value = received_value
WHERE observed_value IS NULL;

CREATE INDEX ix_inventory_temperature_evaluation_fulfillment_source
    ON warehouse.inventory_temperature_evaluation (tenant_id, workspace_id, source_subject_id, source_subject_version)
    WHERE source_type = 'FULFILLMENT';

COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.observed_value IS
    'Manual Celsius observation for any temperature evaluation; received_value remains the legacy receiving field.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.source_type IS
    'BC-05-owned source classification for the evaluation: receiving or fulfillment.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.source_subject_id IS
    'Opaque source aggregate identifier, such as a Fulfillment UUID; no cross-context foreign key is used.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.source_subject_version IS
    'Source aggregate version captured when the manual temperature evidence was recorded.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.temperature_evidence_id IS
    'Opaque BC-06 temperature evidence identifier; there is intentionally no cross-context foreign key.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.expected_lot_version IS
    'Inventory-lot version checked before the atomic preventive HOLD transition.';
