ALTER TABLE payments.stripe_event_inbox
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp;

CREATE INDEX IF NOT EXISTS ix_stripe_event_retry_queue
    ON payments.stripe_event_inbox (status, next_attempt_at, received_at, event_id);
