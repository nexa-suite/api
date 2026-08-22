ALTER TABLE integration.outbox_event
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

UPDATE integration.outbox_event
SET processing_started_at = COALESCE(processing_started_at, created_at),
    lease_until = COALESCE(lease_until, created_at + interval '10 minutes')
WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS ix_outbox_lease_queue
    ON integration.outbox_event (status, lease_until, next_attempt_at, created_at, event_id);

ALTER TABLE payments.stripe_event_inbox
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

UPDATE payments.stripe_event_inbox
SET processing_started_at = COALESCE(processing_started_at, received_at),
    lease_until = COALESCE(lease_until, received_at + interval '10 minutes')
WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS ix_stripe_event_lease_queue
    ON payments.stripe_event_inbox (status, lease_until, next_attempt_at, received_at, event_id);
