ALTER TABLE logistics.fulfillment_handoff_evidence
    ALTER COLUMN warehouse_actor_membership_id DROP NOT NULL,
    ADD COLUMN dispatch_actor_membership_id UUID,
    ADD CONSTRAINT ck_fulfillment_handoff_evidence_actor
        CHECK (warehouse_actor_membership_id IS NOT NULL OR dispatch_actor_membership_id IS NOT NULL);

COMMENT ON COLUMN logistics.fulfillment_handoff_evidence.warehouse_actor_membership_id IS
    'Legacy historical Warehouse actor field. Existing values are retained; new handoffs leave this null unless a separately verified physical-transfer actor is recorded.';

COMMENT ON COLUMN logistics.fulfillment_handoff_evidence.dispatch_actor_membership_id IS
    'Authenticated Dispatch actor that performed the handoff command; null is retained for historical records without authoritative actor evidence.';
