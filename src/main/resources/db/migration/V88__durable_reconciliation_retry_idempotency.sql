-- Manual refund retries are durable commands. The request hash prevents key
-- reuse with another intent; the JSON result makes timeout/restart replay safe.
CREATE TABLE payments.reconciliation_refund_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    case_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    result_json JSONB,
    failure_kind VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, workspace_id, case_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_reconciliation_retry_idempotency_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_reconciliation_retry_idempotency_case
        FOREIGN KEY (case_id) REFERENCES payments.payment_reconciliation_case (id),
    CONSTRAINT ck_reconciliation_retry_idempotency_key
        CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_reconciliation_retry_idempotency_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reconciliation_retry_idempotency_status
        CHECK (result_status IN ('IN_PROGRESS', 'SUCCESS', 'FAILURE')),
    CONSTRAINT ck_reconciliation_retry_idempotency_result
        CHECK ((result_status = 'IN_PROGRESS' AND result_json IS NULL AND completed_at IS NULL)
            OR (result_status IN ('SUCCESS', 'FAILURE') AND result_json IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_reconciliation_retry_idempotency_created
    ON payments.reconciliation_refund_idempotency (tenant_id, workspace_id, created_at);

ALTER TABLE payments.reconciliation_refund_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.reconciliation_refund_idempotency FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_retry_idempotency_tenant_workspace_scope
    ON payments.reconciliation_refund_idempotency
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON payments.reconciliation_refund_idempotency TO nexa_runtime;
    END IF;
END;
$$;
