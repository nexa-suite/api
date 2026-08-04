CREATE SCHEMA IF NOT EXISTS payments;

CREATE TABLE IF NOT EXISTS payments.credit_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    credit_limit NUMERIC(19,4) NOT NULL DEFAULT 0,
    credit_exposure NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved_exposure NUMERIC(19,4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_credit_account_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_credit_account_client_currency UNIQUE (tenant_id, workspace_id, client_account_id, currency),
    CONSTRAINT fk_credit_account_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_credit_account_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT ck_credit_account_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_credit_account_amounts CHECK (credit_limit >= 0 AND credit_exposure >= 0 AND reserved_exposure >= 0 AND credit_exposure + reserved_exposure <= credit_limit),
    CONSTRAINT ck_credit_account_status CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED'))
);

CREATE TABLE IF NOT EXISTS payments.receivable (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id UUID NOT NULL,
    receivable_number VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    amount_paid NUMERIC(19,4) NOT NULL DEFAULT 0,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_receivable_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_receivable_number UNIQUE (tenant_id, workspace_id, receivable_number),
    CONSTRAINT uq_receivable_subject UNIQUE (tenant_id, workspace_id, subject_type, subject_id),
    CONSTRAINT fk_receivable_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_receivable_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT ck_receivable_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_receivable_amounts CHECK (amount > 0 AND amount_paid >= 0 AND amount_paid <= amount),
    CONSTRAINT ck_receivable_status CHECK (status IN ('OPEN','PARTIALLY_PAID','PAID','VOID','OVERDUE'))
);

CREATE INDEX IF NOT EXISTS ix_receivable_scope_status ON payments.receivable
    (tenant_id, workspace_id, client_account_id, status, due_at, id);

CREATE TABLE IF NOT EXISTS payments.payment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    receivable_id UUID NOT NULL,
    created_by_membership_id UUID NOT NULL,
    method VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    provider VARCHAR(32),
    provider_payment_intent_id VARCHAR(160),
    client_secret VARCHAR(500),
    idempotency_key VARCHAR(160) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_payment_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_payment_actor_idempotency UNIQUE (tenant_id, workspace_id, created_by_membership_id, idempotency_key),
    CONSTRAINT uq_payment_provider_reference UNIQUE (provider, provider_payment_intent_id),
    CONSTRAINT fk_payment_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_payment_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT fk_payment_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT fk_payment_membership FOREIGN KEY (workspace_id, created_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_payment_method CHECK (method IN ('CARD_STRIPE','BANK_TRANSFER','CREDIT_LINE')),
    CONSTRAINT ck_payment_status CHECK (status IN ('CREATED','REQUIRES_ACTION','PROCESSING','SUCCEEDED','FAILED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED')),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_metadata_size CHECK (octet_length(metadata::text) <= 32000)
);

CREATE INDEX IF NOT EXISTS ix_payment_scope_status ON payments.payment
    (tenant_id, workspace_id, client_account_id, status, created_at DESC, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_provider_reference_not_null
    ON payments.payment (provider, provider_payment_intent_id)
    WHERE provider_payment_intent_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS payments.payment_attempt (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    provider_reference VARCHAR(160),
    failure_code VARCHAR(120),
    failure_detail VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payment_attempt_number UNIQUE (payment_id, attempt_number),
    CONSTRAINT fk_payment_attempt_payment FOREIGN KEY (tenant_id, workspace_id, payment_id)
        REFERENCES payments.payment (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_payment_attempt_status CHECK (status IN ('CREATED','REQUIRES_ACTION','PROCESSING','SUCCEEDED','FAILED','CANCELLED'))
);

CREATE TABLE IF NOT EXISTS payments.receivable_allocation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    receivable_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_receivable_allocation_payment UNIQUE (payment_id),
    CONSTRAINT fk_receivable_allocation_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT fk_receivable_allocation_payment FOREIGN KEY (tenant_id, workspace_id, payment_id)
        REFERENCES payments.payment (tenant_id, workspace_id, id),
    CONSTRAINT ck_receivable_allocation_amount CHECK (amount > 0)
);

CREATE TABLE IF NOT EXISTS payments.credit_reservation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    receivable_id UUID,
    payment_id UUID,
    amount NUMERIC(19,4) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    CONSTRAINT uq_credit_reservation_idempotency UNIQUE (tenant_id, workspace_id, credit_account_id, idempotency_key),
    CONSTRAINT fk_credit_reservation_account FOREIGN KEY (tenant_id, workspace_id, credit_account_id)
        REFERENCES payments.credit_account (tenant_id, workspace_id, id),
    CONSTRAINT fk_credit_reservation_receivable FOREIGN KEY (tenant_id, workspace_id, receivable_id)
        REFERENCES payments.receivable (tenant_id, workspace_id, id),
    CONSTRAINT fk_credit_reservation_payment FOREIGN KEY (tenant_id, workspace_id, payment_id)
        REFERENCES payments.payment (tenant_id, workspace_id, id),
    CONSTRAINT ck_credit_reservation_amount CHECK (amount > 0),
    CONSTRAINT ck_credit_reservation_status CHECK (status IN ('RESERVED','RELEASED','CONSUMED','EXPIRED'))
);

CREATE TABLE IF NOT EXISTS payments.stripe_event_inbox (
    event_id VARCHAR(160) PRIMARY KEY,
    event_type VARCHAR(160) NOT NULL,
    payment_intent_id VARCHAR(160),
    payment_status VARCHAR(32),
    amount_minor BIGINT,
    currency CHAR(3),
    signature_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    failure_detail VARCHAR(1000),
    CONSTRAINT ck_stripe_event_status CHECK (status IN ('RECEIVED','PROCESSING','PROCESSED','IGNORED','FAILED')),
    CONSTRAINT ck_stripe_event_amount CHECK (amount_minor IS NULL OR amount_minor > 0),
    CONSTRAINT ck_stripe_event_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_stripe_event_signature CHECK (signature_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS ix_stripe_event_queue ON payments.stripe_event_inbox (status, received_at, event_id);

CREATE TABLE IF NOT EXISTS payments.payment_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payment_event_key UNIQUE (payment_id, event_key),
    CONSTRAINT fk_payment_event_payment FOREIGN KEY (tenant_id, workspace_id, payment_id)
        REFERENCES payments.payment (tenant_id, workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_payment_event_scope_time ON payments.payment_event
    (tenant_id, workspace_id, occurred_at DESC, id);

ALTER TABLE sales.client_account
    ADD COLUMN IF NOT EXISTS credit_currency CHAR(3) NOT NULL DEFAULT 'PEN';

INSERT INTO payments.credit_account
    (id, tenant_id, workspace_id, client_account_id, currency, credit_limit, credit_exposure, reserved_exposure, status, created_at, updated_at)
SELECT md5(c.id::text || ':credit:' || c.credit_currency)::uuid, c.tenant_id, c.workspace_id, c.id,
       c.credit_currency, c.credit_limit, greatest(c.current_commercial_exposure, 0), 0,
       CASE WHEN c.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'SUSPENDED' END, current_timestamp, current_timestamp
FROM sales.client_account c
ON CONFLICT (tenant_id, workspace_id, client_account_id, currency) DO NOTHING;
