-- New wallet refunds retain the required operator reason and acting membership.
ALTER TABLE payments.buyer_wallet_ledger_entry
    ADD COLUMN reason VARCHAR(500),
    ADD COLUMN actor_membership_id UUID;

ALTER TABLE payments.buyer_wallet_ledger_entry
    ADD CONSTRAINT ck_buyer_wallet_refund_evidence CHECK (
        (entry_type = 'REFUND' AND reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 500
            AND actor_membership_id IS NOT NULL)
        OR (entry_type <> 'REFUND' AND reason IS NULL AND actor_membership_id IS NULL)
    ) NOT VALID;
