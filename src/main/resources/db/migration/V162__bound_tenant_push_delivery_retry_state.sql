-- Keep push retry/dead-letter state per Tenant subscription/event delivery.
-- The source outbox event remains shared with other consumers.
ALTER TABLE notifications.push_delivery_claim
    DROP CONSTRAINT ck_push_delivery_claim_status,
    DROP CONSTRAINT ck_push_delivery_claim_state;

ALTER TABLE notifications.push_delivery_claim
    ADD CONSTRAINT ck_push_delivery_claim_status
        CHECK (status IN ('CLAIMED', 'SENT', 'DEAD_LETTER')),
    ADD CONSTRAINT ck_push_delivery_claim_state
        CHECK (
            (status = 'CLAIMED' AND lease_until IS NOT NULL)
            OR (status IN ('SENT', 'DEAD_LETTER') AND claim_token IS NULL AND lease_until IS NULL)
        );
