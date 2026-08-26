-- v0.13 closes the remaining durable worker claim gaps. Existing rows are
-- immediately retryable; no in-flight external result is treated as committed.
ALTER TABLE iam.security_notification_outbox
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

UPDATE iam.security_notification_outbox
SET lease_until = COALESCE(lease_until, processing_started_at + interval '10 minutes')
WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS ix_security_outbox_lease_queue
    ON iam.security_notification_outbox (status, lease_until, next_attempt_at, created_at, id);

ALTER TABLE payments.payment_reconciliation_case
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

ALTER TABLE payments.payment_reconciliation_case
    DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_case_state;

ALTER TABLE payments.payment_reconciliation_case
    ADD CONSTRAINT ck_payment_reconciliation_case_state
    CHECK (state IN ('RECONCILIATION_REQUIRED','REFUND_PENDING','REFUND_PROCESSING','REFUNDED','REFUND_FAILED','RESOLVED'));

CREATE INDEX IF NOT EXISTS ix_payment_reconciliation_case_claim_queue
    ON payments.payment_reconciliation_case (state, lease_until, updated_at, id);
