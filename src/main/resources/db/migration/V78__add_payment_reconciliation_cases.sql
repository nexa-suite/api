-- Durable operator queue for a captured payment whose commercial subject
-- could not be completed. The payment and its history stay immutable; the
-- case records the compensating refund workflow.
CREATE TABLE payments.payment_reconciliation_case (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    receivable_id UUID NOT NULL,
    sales_order_id UUID,
    allocation_status VARCHAR(16) NOT NULL DEFAULT 'UNALLOCATED',
    state VARCHAR(32) NOT NULL DEFAULT 'RECONCILIATION_REQUIRED',
    provider_refund_id VARCHAR(160),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2000),
    operator_note VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uq_payment_reconciliation_case_payment UNIQUE (tenant_id, workspace_id, payment_id),
    CONSTRAINT fk_payment_reconciliation_case_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_payment_reconciliation_case_payment FOREIGN KEY (tenant_id, workspace_id, payment_id)
        REFERENCES payments.payment (tenant_id, workspace_id, id),
    CONSTRAINT fk_payment_reconciliation_case_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT fk_payment_reconciliation_case_order FOREIGN KEY (tenant_id, workspace_id, sales_order_id)
        REFERENCES sales.sales_order (tenant_id, workspace_id, id),
    CONSTRAINT ck_payment_reconciliation_case_allocation CHECK (allocation_status IN ('UNALLOCATED','ALLOCATED')),
    CONSTRAINT ck_payment_reconciliation_case_state CHECK (state IN ('RECONCILIATION_REQUIRED','REFUND_PENDING','REFUNDED','REFUND_FAILED','RESOLVED')),
    CONSTRAINT ck_payment_reconciliation_case_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX ix_payment_reconciliation_case_scope_state
    ON payments.payment_reconciliation_case (tenant_id, workspace_id, state, updated_at DESC, id);

ALTER TABLE payments.payment_reconciliation_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.payment_reconciliation_case FORCE ROW LEVEL SECURITY;
CREATE POLICY payment_reconciliation_case_tenant_workspace_scope
    ON payments.payment_reconciliation_case
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON payments.payment_reconciliation_case TO nexa_runtime;
    END IF;
END;
$$;
