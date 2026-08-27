ALTER TABLE payments.stripe_event_inbox
    DROP CONSTRAINT IF EXISTS ck_stripe_event_status;

ALTER TABLE payments.stripe_event_inbox
    ADD CONSTRAINT ck_stripe_event_status
    CHECK (status IN ('RECEIVED','PROCESSING','PROCESSED','IGNORED','FAILED','DEAD_LETTER'));
