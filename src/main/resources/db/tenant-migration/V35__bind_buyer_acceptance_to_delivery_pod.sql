-- Tenant projection of central V184: authenticated Buyer acceptance binds
-- the membership and immutable receipt fact to the POD and seals it.
-- V3 is a pg_dump restore and leaves row_security=off for the Flyway session.
-- Restore normal behavior before validating constraints. Temporarily remove
-- FORCE only for the migration owner so validation can inspect historical
-- rows; RLS stays enabled and FORCE is restored before this transaction commits.
SET row_security = on;

DO $$
DECLARE
    expected_tables TEXT[] := ARRAY[
        'logistics.buyer_receipt_fact',
        'logistics.proof_of_delivery',
        'sales.client_account_membership'
    ];
    relation_name TEXT;
    relation_owner NAME;
    rls_enabled BOOLEAN;
    rls_forced BOOLEAN;
BEGIN
    FOREACH relation_name IN ARRAY expected_tables LOOP
        SELECT pg_get_userbyid(relation.relowner), relation.relrowsecurity, relation.relforcerowsecurity
          INTO relation_owner, rls_enabled, rls_forced
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE format('%I.%I', schema_row.nspname, relation.relname) = relation_name
           AND relation.relkind = 'r';
        IF relation_owner IS DISTINCT FROM current_user OR NOT coalesce(rls_enabled, FALSE)
           OR NOT coalesce(rls_forced, FALSE) THEN
            RAISE EXCEPTION 'Tenant V35 requires migration ownership and forced RLS on %', relation_name;
        END IF;
    END LOOP;
END;
$$;

ALTER TABLE logistics.buyer_receipt_fact NO FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.proof_of_delivery NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.client_account_membership NO FORCE ROW LEVEL SECURITY;

-- PostgreSQL requires the referenced column list to have a matching unique
-- key. The baseline's workspace_membership_id-only key does not cover this
-- explicit Tenant/Workspace foreign key target.
ALTER TABLE sales.client_account_membership
    ADD CONSTRAINT uq_client_account_membership_tenant_workspace_member
        UNIQUE (tenant_id, workspace_id, workspace_membership_id);

ALTER TABLE logistics.buyer_receipt_fact
    ADD CONSTRAINT fk_buyer_receipt_buyer_account_membership_v17
        FOREIGN KEY (customer_account_id, buyer_membership_id)
        REFERENCES sales.client_account_membership (client_account_id, workspace_membership_id),
    ADD CONSTRAINT uq_buyer_receipt_scope_actor_v17
        UNIQUE (tenant_id, workspace_id, id, buyer_membership_id);

ALTER TABLE logistics.proof_of_delivery
    ADD COLUMN buyer_membership_id UUID,
    ADD COLUMN buyer_receipt_fact_id UUID,
    ALTER COLUMN receiver_name DROP NOT NULL,
    ADD CONSTRAINT fk_pod_buyer_membership_v17
        FOREIGN KEY (tenant_id, workspace_id, buyer_membership_id)
        REFERENCES sales.client_account_membership (tenant_id, workspace_id, workspace_membership_id),
    ADD CONSTRAINT fk_pod_buyer_receipt_v17
        FOREIGN KEY (tenant_id, workspace_id, buyer_receipt_fact_id, buyer_membership_id)
        REFERENCES logistics.buyer_receipt_fact (tenant_id, workspace_id, id, buyer_membership_id),
    ADD CONSTRAINT ck_pod_receiver_identity_v17
        CHECK (receiver_name IS NOT NULL OR buyer_membership_id IS NOT NULL OR status = 'PENDING'),
    ADD CONSTRAINT ck_pod_buyer_receipt_binding_v17
        CHECK ((buyer_receipt_fact_id IS NULL) = (buyer_membership_id IS NULL)),
    ADD CONSTRAINT uq_pod_buyer_receipt_v17
        UNIQUE (tenant_id, workspace_id, buyer_receipt_fact_id);

CREATE INDEX ix_pod_buyer_membership_v17
    ON logistics.proof_of_delivery (tenant_id, workspace_id, buyer_membership_id, delivery_id)
    WHERE buyer_membership_id IS NOT NULL;

COMMENT ON COLUMN logistics.proof_of_delivery.buyer_membership_id IS
    'Authenticated Buyer membership that explicitly accepted the delivery represented by this POD.';
COMMENT ON COLUMN logistics.proof_of_delivery.buyer_receipt_fact_id IS
    'Immutable Buyer receipt fact finalized by this POD.';

ALTER TABLE sales.client_account_membership FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.buyer_receipt_fact FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.proof_of_delivery FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE format('%I.%I', schema_row.nspname, relation.relname) IN (
             'logistics.buyer_receipt_fact',
             'logistics.proof_of_delivery',
             'sales.client_account_membership'
         )
           AND relation.relkind = 'r'
           AND (NOT relation.relrowsecurity OR NOT relation.relforcerowsecurity)
    ) THEN
        RAISE EXCEPTION 'Tenant V35 did not restore forced RLS on every affected table';
    END IF;
END;
$$;

DROP TRIGGER IF EXISTS logistics_pod_lifecycle_v16 ON logistics.proof_of_delivery;

CREATE OR REPLACE FUNCTION logistics.prevent_pod_mutation_buyer_acceptance_v17()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    photo_added BOOLEAN;
    signature_added BOOLEAN;
    evidence_attached BOOLEAN;
    buyer_membership_bound BOOLEAN;
    buyer_receipt_bound BOOLEAN;
    matching_buyer_acceptance BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Proof of delivery is append-only';
    END IF;

    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
       OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.dispatch_order_id IS DISTINCT FROM OLD.dispatch_order_id
       OR NEW.receiver_name IS DISTINCT FROM OLD.receiver_name
       OR NEW.completed_at IS DISTINCT FROM OLD.completed_at
       OR NEW.notes IS DISTINCT FROM OLD.notes
       OR NEW.photo_object_key IS DISTINCT FROM OLD.photo_object_key
       OR NEW.signature_object_key IS DISTINCT FROM OLD.signature_object_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Proof of delivery identity is immutable';
    END IF;

    buyer_membership_bound := OLD.buyer_membership_id IS NULL
        AND NEW.buyer_membership_id IS NOT NULL;
    buyer_receipt_bound := OLD.buyer_receipt_fact_id IS NULL
        AND NEW.buyer_receipt_fact_id IS NOT NULL;
    IF OLD.buyer_membership_id IS NOT NULL
       AND NEW.buyer_membership_id IS DISTINCT FROM OLD.buyer_membership_id THEN
        RAISE EXCEPTION 'Proof of delivery Buyer identity is immutable';
    END IF;
    IF OLD.buyer_receipt_fact_id IS NOT NULL
       AND NEW.buyer_receipt_fact_id IS DISTINCT FROM OLD.buyer_receipt_fact_id THEN
        RAISE EXCEPTION 'Proof of delivery Buyer receipt link is immutable';
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
        IF NEW.sealed_at IS DISTINCT FROM OLD.sealed_at OR buyer_membership_bound OR buyer_receipt_bound THEN
            RAISE EXCEPTION 'Proof of delivery evidence transition is invalid';
        END IF;
        RETURN NEW;
    END IF;

    matching_buyer_acceptance := NEW.buyer_membership_id IS NOT NULL
        AND NEW.buyer_receipt_fact_id IS NOT NULL
        AND EXISTS (
            SELECT 1
            FROM logistics.buyer_receipt_fact receipt
            WHERE receipt.tenant_id = NEW.tenant_id
              AND receipt.workspace_id = NEW.workspace_id
              AND receipt.id = NEW.buyer_receipt_fact_id
              AND receipt.buyer_membership_id = NEW.buyer_membership_id
              AND receipt.decision = 'ACCEPTED'
              AND (
                  receipt.delivery_id = NEW.delivery_id
                  OR (NEW.delivery_id IS NULL AND NEW.dispatch_order_id IS NOT NULL AND EXISTS (
                      SELECT 1
                      FROM logistics.delivery delivery
                      WHERE delivery.tenant_id = receipt.tenant_id
                        AND delivery.workspace_id = receipt.workspace_id
                        AND delivery.id = receipt.delivery_id
                        AND delivery.dispatch_order_id = NEW.dispatch_order_id
                  ))
              )
              AND (NEW.attempt_id IS NULL OR receipt.delivery_attempt_id = NEW.attempt_id)
        );

    IF OLD.status IN ('PENDING','CAPTURED') AND NEW.status = 'SEALED'
       AND NEW.sealed_at IS NOT NULL
       AND matching_buyer_acceptance THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'CAPTURED' AND NEW.status = 'SEALED'
       AND NEW.sealed_at IS NOT NULL
       AND NEW.buyer_membership_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'CAPTURED' AND NEW.status = 'REJECTED'
       AND NOT buyer_membership_bound THEN
        RETURN NEW;
    END IF;

    IF OLD.status IN ('SEALED','COMPLETED') AND NEW.status = OLD.status
       AND NEW.sealed_at IS NOT DISTINCT FROM OLD.sealed_at
       AND (buyer_membership_bound OR buyer_receipt_bound) AND matching_buyer_acceptance THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Proof of delivery transition is invalid';
END;
$$;

CREATE TRIGGER logistics_pod_lifecycle_v17
    BEFORE UPDATE OR DELETE ON logistics.proof_of_delivery FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_pod_mutation_buyer_acceptance_v17();
