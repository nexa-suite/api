-- Driver proof is created only after a successful terminal attempt, then its
-- immutable evidence objects are attached after BC-09 confirms them for the
-- exact ProofOfDelivery subject.
DROP TRIGGER IF EXISTS logistics_pod_lifecycle_v15 ON logistics.proof_of_delivery;

CREATE OR REPLACE FUNCTION logistics.prevent_pod_mutation_driver_evidence_v16()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    photo_added BOOLEAN;
    signature_added BOOLEAN;
    evidence_attached BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Proof of delivery is append-only';
    END IF;

    IF NEW.tenant_id <> OLD.tenant_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.id <> OLD.id OR NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.dispatch_order_id IS DISTINCT FROM OLD.dispatch_order_id
       OR NEW.receiver_name IS DISTINCT FROM OLD.receiver_name
       OR NEW.completed_at IS DISTINCT FROM OLD.completed_at
       OR NEW.notes IS DISTINCT FROM OLD.notes
       OR NEW.photo_object_key IS DISTINCT FROM OLD.photo_object_key
       OR NEW.signature_object_key IS DISTINCT FROM OLD.signature_object_key
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Proof of delivery identity is immutable';
    END IF;

    photo_added := OLD.photo_evidence_object_id IS NULL AND NEW.photo_evidence_object_id IS NOT NULL;
    signature_added := OLD.signature_evidence_object_id IS NULL AND NEW.signature_evidence_object_id IS NOT NULL;
    evidence_attached := photo_added OR signature_added;

    IF (OLD.photo_evidence_object_id IS NOT NULL AND NEW.photo_evidence_object_id IS DISTINCT FROM OLD.photo_evidence_object_id)
       OR (OLD.signature_evidence_object_id IS NOT NULL AND NEW.signature_evidence_object_id IS DISTINCT FROM OLD.signature_evidence_object_id)
       OR (photo_added AND NOT NEW.photo_evidence_declared)
       OR (signature_added AND NOT NEW.signature_evidence_declared)
       OR (NOT photo_added AND NEW.photo_evidence_declared IS DISTINCT FROM OLD.photo_evidence_declared)
       OR (NOT signature_added AND NEW.signature_evidence_declared IS DISTINCT FROM OLD.signature_evidence_declared) THEN
        RAISE EXCEPTION 'Proof of delivery evidence is immutable';
    END IF;

    IF evidence_attached THEN
        IF photo_added = signature_added OR NOT (
            (OLD.status = 'PENDING' AND NEW.status = 'CAPTURED')
            OR (OLD.status = 'CAPTURED' AND NEW.status = 'CAPTURED')
        ) THEN
            RAISE EXCEPTION 'Proof of delivery evidence transition is invalid';
        END IF;
        IF NEW.sealed_at IS DISTINCT FROM OLD.sealed_at THEN
            RAISE EXCEPTION 'Proof of delivery sealing is a separate transition';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'CAPTURED' AND NEW.status IN ('SEALED','REJECTED') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Proof of delivery transition is invalid';
END;
$$;

CREATE TRIGGER logistics_pod_lifecycle_v16
    BEFORE UPDATE OR DELETE ON logistics.proof_of_delivery FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_pod_mutation_driver_evidence_v16();
