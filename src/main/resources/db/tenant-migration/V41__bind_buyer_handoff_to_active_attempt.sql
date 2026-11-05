-- Tenant database projection of central V188. Active attempt reference converts to terminal
-- attempt binding atomically with the outcome transaction.
-- Keep handoff QR bound to an active attempt until its immutable terminal outcome is written.
-- Outcome closure converts the same attempt identity to the terminal foreign key in one transaction.
ALTER TABLE logistics.delivery_active_attempt
    ADD CONSTRAINT uq_delivery_active_attempt_scope_binding
        UNIQUE (tenant_id, workspace_id, id, delivery_id);

ALTER TABLE logistics.delivery_handoff_token
    ALTER COLUMN delivery_attempt_id DROP NOT NULL,
    ADD COLUMN delivery_active_attempt_id UUID;

ALTER TABLE logistics.delivery_handoff_token
    DROP CONSTRAINT fk_handoff_token_attempt,
    ADD CONSTRAINT ck_handoff_token_attempt_binding_v188
        CHECK ((delivery_attempt_id IS NOT NULL) <> (delivery_active_attempt_id IS NOT NULL)),
    ADD CONSTRAINT fk_handoff_token_attempt_v188
        FOREIGN KEY (tenant_id, workspace_id, delivery_attempt_id, delivery_id)
        REFERENCES logistics.delivery_attempt (tenant_id, workspace_id, id, delivery_id),
    ADD CONSTRAINT fk_handoff_token_active_attempt_v188
        FOREIGN KEY (tenant_id, workspace_id, delivery_active_attempt_id, delivery_id)
        REFERENCES logistics.delivery_active_attempt (tenant_id, workspace_id, id, delivery_id);

DROP INDEX logistics.uq_handoff_token_active_delivery_attempt;
CREATE UNIQUE INDEX uq_handoff_token_active_delivery_attempt
    ON logistics.delivery_handoff_token
        (tenant_id, workspace_id, delivery_id,
         (COALESCE(delivery_attempt_id, delivery_active_attempt_id)))
    WHERE status = 'ACTIVE';

DROP TRIGGER IF EXISTS logistics_delivery_handoff_token_lifecycle_v17
    ON logistics.delivery_handoff_token;

CREATE OR REPLACE FUNCTION logistics.prevent_delivery_handoff_token_mutation_v188()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Delivery handoff token binding is immutable';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
       OR NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.customer_account_id IS DISTINCT FROM OLD.customer_account_id
       OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
       OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
       OR NEW.issuer_membership_id IS DISTINCT FROM OLD.issuer_membership_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Delivery handoff token binding is immutable';
    END IF;

    IF OLD.delivery_attempt_id IS NULL
       AND OLD.delivery_active_attempt_id IS NOT NULL
       AND NEW.delivery_attempt_id = OLD.delivery_active_attempt_id
       AND NEW.delivery_active_attempt_id IS NULL
       AND (NEW.status IS NOT DISTINCT FROM OLD.status
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'REPLACED')) THEN
        RETURN NEW;
    END IF;

    IF NEW.delivery_attempt_id IS DISTINCT FROM OLD.delivery_attempt_id
       OR NEW.delivery_active_attempt_id IS DISTINCT FROM OLD.delivery_active_attempt_id
       OR OLD.status <> 'ACTIVE'
       OR NEW.status NOT IN ('REPLACED', 'EXPIRED', 'CONSUMED') THEN
        RAISE EXCEPTION 'Delivery handoff token lifecycle transition is invalid';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER logistics_delivery_handoff_token_lifecycle_v188
    BEFORE UPDATE OR DELETE ON logistics.delivery_handoff_token
    FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_delivery_handoff_token_mutation_v188();
