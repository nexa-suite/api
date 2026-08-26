-- Preserve immutable command outcomes for exact idempotency replay. Legacy
-- rows remain readable through their resource/version fallback until a new
-- command writes its response snapshot.
ALTER TABLE sales.idempotency_record
    ADD COLUMN IF NOT EXISTS response_json TEXT;

-- Idempotency records are append-only in v0.13. Keep the immutable command
-- row untouched and persist the immutable response snapshot as a second
-- insert-only row. This also lets old records remain replayable by resource
-- and version while new commands replay the exact original response.
CREATE TABLE IF NOT EXISTS sales.idempotency_response (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_sales_idempotency_response PRIMARY KEY (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key),
    CONSTRAINT fk_sales_idempotency_response_record FOREIGN KEY (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key)
        REFERENCES sales.idempotency_record (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key)
);

CREATE TRIGGER idempotency_response_append_only
    BEFORE UPDATE OR DELETE ON sales.idempotency_response
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE sales.idempotency_response ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.idempotency_response FORCE ROW LEVEL SECURITY;
CREATE POLICY sales_idempotency_response_tenant_workspace_scope
    ON sales.idempotency_response
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

CREATE INDEX ix_sales_idempotency_response_created
    ON sales.idempotency_response (tenant_id, workspace_id, created_at DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON sales.idempotency_response TO nexa_runtime;
    END IF;
END;
$$;

-- A v0.13 credit reservation can exist without the v1 commitment projection
-- only when the released history is already inconsistent. Fail the upgrade
-- with an actionable diagnosis instead of making the reservation invisible to
-- the v0.14 commitment authority.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payments.credit_reservation reservation
        WHERE reservation.status = 'RESERVED'
          AND reservation.purchase_request_id IS NOT NULL
          AND reservation.commercial_commitment_id IS NULL
          AND NOT EXISTS (
              SELECT 1
              FROM sales.commercial_commitment commitment
              WHERE commitment.tenant_id = reservation.tenant_id
                AND commitment.workspace_id = reservation.workspace_id
                AND commitment.purchase_request_id = reservation.purchase_request_id
          )
    ) THEN
        RAISE EXCEPTION 'V90 cannot link active credit reservations: legacy reservation has no Commercial Commitment';
    END IF;
END;
$$;
