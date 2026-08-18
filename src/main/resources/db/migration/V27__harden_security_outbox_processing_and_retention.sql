ALTER TABLE iam.security_notification_outbox
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD COLUMN locked_by VARCHAR(128),
    ADD COLUMN delivery_key VARCHAR(128),
    ADD COLUMN payload_key_version VARCHAR(64) NOT NULL DEFAULT 'v1';

UPDATE iam.security_notification_outbox
SET delivery_key = id::text
WHERE delivery_key IS NULL;

ALTER TABLE iam.security_notification_outbox
    ALTER COLUMN delivery_key SET NOT NULL;

ALTER TABLE iam.security_notification_outbox
    ADD CONSTRAINT uq_security_outbox_delivery_key UNIQUE (delivery_key);

CREATE INDEX ix_security_outbox_claim
    ON iam.security_notification_outbox (status, next_attempt_at, processing_started_at, created_at, id);

COMMENT ON COLUMN iam.security_notification_outbox.processing_started_at IS
    'Claim timestamp used for stuck-work recovery; never use created_at for recovery.';
COMMENT ON COLUMN iam.security_notification_outbox.payload_key_version IS
    'Encryption key version used for the ciphertext; supports controlled rotation.';
