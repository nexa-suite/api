ALTER TABLE warehouse.inventory_lot
    ADD COLUMN temperature_evidence_object_id UUID,
    ADD COLUMN temperature_recorded_by_membership_id UUID,
    ADD COLUMN temperature_recorded_at TIMESTAMPTZ;

ALTER TABLE warehouse.inventory_temperature_evaluation
    ADD COLUMN evidence_object_id UUID,
    ADD COLUMN affected_quantity NUMERIC(19,4),
    ADD COLUMN actor_membership_id UUID;

ALTER TABLE warehouse.inventory_temperature_evaluation
    ADD CONSTRAINT ck_inventory_temperature_evaluation_affected_quantity
        CHECK (affected_quantity IS NULL OR affected_quantity > 0);

CREATE INDEX ix_inventory_temperature_evaluation_evidence
    ON warehouse.inventory_temperature_evaluation (tenant_id, workspace_id, evidence_object_id)
    WHERE evidence_object_id IS NOT NULL;

COMMENT ON COLUMN warehouse.inventory_lot.temperature_evidence_object_id IS
    'Optional BC-09 photo evidence for the manual Celsius receiving observation; validated through the BC-09 public query.';
COMMENT ON COLUMN warehouse.inventory_lot.temperature_recorded_by_membership_id IS
    'Membership that recorded the manual Celsius receiving observation; null for legacy lots without a reading.';
COMMENT ON COLUMN warehouse.inventory_lot.temperature_recorded_at IS
    'Time the manual Celsius receiving observation was recorded; null for legacy lots without a reading.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.evidence_object_id IS
    'Required exact Warehouse-bound BC-09 photo evidence for new excursion evaluations; legacy rows may be null.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.affected_quantity IS
    'Quantity placed on preventive HOLD by this receiving excursion; legacy rows may be null.';
COMMENT ON COLUMN warehouse.inventory_temperature_evaluation.actor_membership_id IS
    'Membership that recorded this manual receiving temperature; legacy rows may be null.';
