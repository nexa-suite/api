-- Tenant-local intent and verified callback evidence. Monetary credit itself is
-- written only to buyer_wallet_account and the append-only wallet ledger.
CREATE TABLE payments.buyer_wallet_recharge (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_identity_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    provider_code VARCHAR(16) NOT NULL DEFAULT 'STRIPE',
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'PEN',
    provider_payment_intent_id VARCHAR(255),
    idempotency_key VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PREPARING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_buyer_wallet_recharge_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_wallet_recharge_idempotency UNIQUE
        (tenant_id, workspace_id, buyer_identity_id, idempotency_key),
    CONSTRAINT fk_buyer_wallet_recharge_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT ck_buyer_wallet_recharge_provider CHECK (provider_code = 'STRIPE'),
    CONSTRAINT ck_buyer_wallet_recharge_amount CHECK (amount_minor BETWEEN 1 AND 99999999),
    CONSTRAINT ck_buyer_wallet_recharge_currency CHECK (currency = 'PEN'),
    CONSTRAINT ck_buyer_wallet_recharge_idempotency CHECK
        (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_buyer_wallet_recharge_status CHECK
        (status IN ('PREPARING', 'AWAITING_PAYMENT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_buyer_wallet_recharge_terminal_time CHECK (
        (status IN ('PREPARING', 'AWAITING_PAYMENT') AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED') AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_buyer_wallet_recharge_provider_intent
    ON payments.buyer_wallet_recharge (provider_code, provider_payment_intent_id)
    WHERE provider_payment_intent_id IS NOT NULL;
CREATE INDEX ix_buyer_wallet_recharge_buyer_history
    ON payments.buyer_wallet_recharge (tenant_id, workspace_id, buyer_identity_id, created_at DESC, id);

CREATE TABLE payments.buyer_wallet_recharge_processed_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    recharge_id UUID NOT NULL,
    provider_code VARCHAR(16) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    provider_payment_intent_id VARCHAR(255),
    event_type VARCHAR(160) NOT NULL,
    payment_status VARCHAR(32),
    amount_minor BIGINT,
    currency CHAR(3),
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64),
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_buyer_wallet_recharge_event_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_wallet_recharge_event_provider_id UNIQUE
        (tenant_id, workspace_id, provider_code, provider_event_id),
    CONSTRAINT fk_buyer_wallet_recharge_event_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT ck_buyer_wallet_recharge_event_provider CHECK (provider_code = 'STRIPE'),
    CONSTRAINT ck_buyer_wallet_recharge_event_outcome CHECK
        (outcome IN ('APPLIED', 'IGNORED', 'REJECTED')),
    CONSTRAINT ck_buyer_wallet_recharge_event_amount CHECK
        (amount_minor IS NULL OR amount_minor >= 0),
    CONSTRAINT ck_buyer_wallet_recharge_event_currency CHECK
        (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_buyer_wallet_recharge_event_reason CHECK
        ((outcome = 'REJECTED' AND reason_code IS NOT NULL)
            OR (outcome <> 'REJECTED' AND reason_code IS NULL))
);

CREATE INDEX ix_buyer_wallet_recharge_event_recharge
    ON payments.buyer_wallet_recharge_processed_event
    (tenant_id, workspace_id, recharge_id, processed_at, id);

CREATE FUNCTION payments.guard_buyer_wallet_recharge_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Buyer wallet recharge facts cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.id, NEW.tenant_id, NEW.workspace_id, NEW.buyer_identity_id,
           NEW.buyer_membership_id, NEW.provider_code, NEW.amount_minor, NEW.currency,
           NEW.idempotency_key, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.tenant_id, OLD.workspace_id, OLD.buyer_identity_id,
           OLD.buyer_membership_id, OLD.provider_code, OLD.amount_minor, OLD.currency,
           OLD.idempotency_key, OLD.created_at)
       OR (OLD.provider_payment_intent_id IS NOT NULL
           AND NEW.provider_payment_intent_id IS DISTINCT FROM OLD.provider_payment_intent_id)
       OR NOT (
           NEW.status = OLD.status
           OR (OLD.status = 'PREPARING' AND NEW.status IN
               ('AWAITING_PAYMENT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'))
           OR (OLD.status = 'AWAITING_PAYMENT' AND NEW.status IN
               ('SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'))
       ) THEN
        RAISE EXCEPTION 'Buyer wallet recharge permits only immutable provider binding and terminal status transitions'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER buyer_wallet_recharge_transition_guard
    BEFORE UPDATE OR DELETE ON payments.buyer_wallet_recharge
    FOR EACH ROW EXECUTE FUNCTION payments.guard_buyer_wallet_recharge_transition();

CREATE FUNCTION payments.reject_buyer_wallet_recharge_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Buyer wallet recharge provider-event evidence is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER buyer_wallet_recharge_event_append_only
    BEFORE UPDATE OR DELETE ON payments.buyer_wallet_recharge_processed_event
    FOR EACH ROW EXECUTE FUNCTION payments.reject_buyer_wallet_recharge_event_mutation();

ALTER TABLE payments.buyer_wallet_recharge ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_recharge FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_recharge_processed_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_recharge_processed_event FORCE ROW LEVEL SECURITY;

CREATE POLICY buyer_wallet_recharge_tenant_workspace_scope ON payments.buyer_wallet_recharge
    USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''));
CREATE POLICY buyer_wallet_recharge_event_tenant_workspace_scope
    ON payments.buyer_wallet_recharge_processed_event
    USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''));

REVOKE ALL PRIVILEGES ON payments.buyer_wallet_recharge,
    payments.buyer_wallet_recharge_processed_event FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON payments.buyer_wallet_recharge TO nexa_runtime;
        GRANT UPDATE (provider_payment_intent_id, status, updated_at, completed_at)
            ON payments.buyer_wallet_recharge TO nexa_runtime;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_wallet_recharge_worker') THEN
        GRANT SELECT ON nexa_platform.tenant_business_database_identity,
            nexa_platform.tenant_workspace_scope_anchor TO nexa_wallet_recharge_worker;
        GRANT SELECT ON payments.buyer_wallet_recharge TO nexa_wallet_recharge_worker;
        GRANT UPDATE (provider_payment_intent_id, status, updated_at, completed_at)
            ON payments.buyer_wallet_recharge TO nexa_wallet_recharge_worker;
        GRANT SELECT, INSERT ON payments.buyer_wallet_recharge_processed_event
            TO nexa_wallet_recharge_worker;
        GRANT SELECT ON payments.buyer_wallet_account TO nexa_wallet_recharge_worker;
        GRANT INSERT (id, tenant_id, workspace_id, buyer_identity_id, currency)
            ON payments.buyer_wallet_account TO nexa_wallet_recharge_worker;
        GRANT UPDATE (posted_balance, version, updated_at)
            ON payments.buyer_wallet_account TO nexa_wallet_recharge_worker;
        GRANT SELECT, INSERT ON payments.buyer_wallet_ledger_entry TO nexa_wallet_recharge_worker;
    END IF;
END;
$$;
