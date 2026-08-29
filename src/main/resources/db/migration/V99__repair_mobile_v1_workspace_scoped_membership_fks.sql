-- Repair the pre-release V95 variant that used membership IDs without the
-- workspace scope. V95 remains checksum-stable; this forward migration makes
-- the physical integrity contract workspace-scoped for fresh and upgraded DBs.

ALTER TABLE notifications.push_subscription
    DROP CONSTRAINT IF EXISTS fk_push_subscription_recipient,
    ADD CONSTRAINT fk_push_subscription_recipient FOREIGN KEY (workspace_id, recipient_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

ALTER TABLE notifications.push_subscription_command_idempotency
    DROP CONSTRAINT IF EXISTS fk_push_subscription_idempotency_actor,
    ADD CONSTRAINT fk_push_subscription_idempotency_actor FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);
