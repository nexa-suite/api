-- v0.17 Mobile V1 integrity hardening. V93/V94 are already released locally
-- and remain immutable; these constraints are forward-only additions.

ALTER TABLE logistics.delivery_handoff_token
    ADD CONSTRAINT fk_handoff_token_customer FOREIGN KEY (tenant_id, workspace_id, customer_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_handoff_token_issuer FOREIGN KEY (workspace_id, issuer_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

CREATE UNIQUE INDEX uq_handoff_token_binding
    ON logistics.delivery_handoff_token (tenant_id, workspace_id, id, delivery_id, delivery_attempt_id, customer_account_id);

ALTER TABLE logistics.buyer_receipt_fact
    ADD CONSTRAINT fk_buyer_receipt_handoff_binding FOREIGN KEY
        (tenant_id, workspace_id, handoff_token_id, delivery_id, delivery_attempt_id, customer_account_id)
        REFERENCES logistics.delivery_handoff_token
        (tenant_id, workspace_id, id, delivery_id, delivery_attempt_id, customer_account_id),
    ADD CONSTRAINT fk_buyer_receipt_customer FOREIGN KEY (tenant_id, workspace_id, customer_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_buyer_receipt_buyer FOREIGN KEY (workspace_id, buyer_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    ADD CONSTRAINT ck_buyer_receipt_dispute_reason CHECK (
        decision <> 'DISPUTED' OR (reason IS NOT NULL AND length(btrim(reason)) > 0)
    );

ALTER TABLE notifications.push_subscription
    ADD CONSTRAINT fk_push_subscription_recipient FOREIGN KEY (workspace_id, recipient_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    ADD CONSTRAINT fk_push_subscription_user FOREIGN KEY (user_id)
        REFERENCES iam.user_account (id);

ALTER TABLE notifications.push_subscription_command_idempotency
    ADD CONSTRAINT fk_push_subscription_idempotency_actor FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

CREATE UNIQUE INDEX uq_push_attempt_delivery
    ON notifications.push_delivery_attempt
    (tenant_id, workspace_id, event_id, subscription_id, attempt_number);
