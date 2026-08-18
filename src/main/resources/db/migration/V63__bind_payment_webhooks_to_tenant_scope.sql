-- The webhook queue is intentionally outside tenant RLS so the intake endpoint
-- can accept a signed event without an application tenant context. The worker
-- must nevertheless restore the provider-bound scope before touching payments.
ALTER TABLE payments.stripe_event_inbox
    ADD COLUMN IF NOT EXISTS tenant_id UUID,
    ADD COLUMN IF NOT EXISTS workspace_id UUID;

ALTER TABLE payments.stripe_event_inbox
    ADD CONSTRAINT ck_stripe_event_scope_pair
    CHECK ((tenant_id IS NULL AND workspace_id IS NULL)
        OR (tenant_id IS NOT NULL AND workspace_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS ix_stripe_event_scope_queue
    ON payments.stripe_event_inbox (tenant_id, workspace_id, status, next_attempt_at, received_at, event_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_evidence_scope_id
    ON business_documents.evidence_object (tenant_id, workspace_id, id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_payment_bank_transfer_proof_evidence'
    ) THEN
        ALTER TABLE payments.payment
            ADD CONSTRAINT fk_payment_bank_transfer_proof_evidence
            FOREIGN KEY (tenant_id, workspace_id, bank_transfer_proof_evidence_id)
            REFERENCES business_documents.evidence_object (tenant_id, workspace_id, id);
    END IF;
END;
$$;
