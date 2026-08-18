-- Bank-transfer review is a retryable command. Persist its key and action so
-- the same review cannot settle or reject a payment twice after a retry.
ALTER TABLE payments.payment
    ADD COLUMN IF NOT EXISTS review_idempotency_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS review_action VARCHAR(24);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_review_idempotency
    ON payments.payment (tenant_id, workspace_id, id, review_idempotency_key)
    WHERE review_idempotency_key IS NOT NULL;

ALTER TABLE payments.payment
    ADD CONSTRAINT ck_payment_review_action
    CHECK (review_action IS NULL OR review_action IN ('APPROVE','REJECT','RECONCILE'));
