-- Tenant-local BC-08 Buyer stored funds. Human identity remains an opaque
-- BC-01 reference; no central identity or membership data is copied here.
CREATE TABLE payments.buyer_wallet_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_identity_id UUID NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'PEN',
    posted_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT uq_buyer_wallet_account_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_wallet_account_identity UNIQUE (tenant_id, buyer_identity_id, currency),
    CONSTRAINT uq_buyer_wallet_account_scoped_identity UNIQUE
        (tenant_id, workspace_id, buyer_identity_id, currency),
    CONSTRAINT fk_buyer_wallet_account_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT ck_buyer_wallet_account_currency CHECK (currency = 'PEN'),
    CONSTRAINT ck_buyer_wallet_account_balances CHECK (
        posted_balance >= 0 AND reserved_balance >= 0 AND reserved_balance <= posted_balance
    ),
    CONSTRAINT ck_buyer_wallet_account_version CHECK (version >= 0)
);

CREATE TABLE payments.buyer_wallet_reservation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_identity_id UUID NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'PEN',
    sales_order_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    reserve_idempotency_key VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    consumed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    CONSTRAINT uq_buyer_wallet_reservation_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_wallet_reservation_order UNIQUE (tenant_id, workspace_id, sales_order_id),
    CONSTRAINT uq_buyer_wallet_reservation_idempotency UNIQUE
        (tenant_id, workspace_id, buyer_identity_id, reserve_idempotency_key),
    CONSTRAINT fk_buyer_wallet_reservation_account FOREIGN KEY
        (tenant_id, workspace_id, buyer_identity_id, currency)
        REFERENCES payments.buyer_wallet_account
        (tenant_id, workspace_id, buyer_identity_id, currency) ON DELETE RESTRICT,
    CONSTRAINT fk_buyer_wallet_reservation_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT ck_buyer_wallet_reservation_currency CHECK (currency = 'PEN'),
    CONSTRAINT ck_buyer_wallet_reservation_amount CHECK (amount > 0),
    CONSTRAINT ck_buyer_wallet_reservation_idempotency CHECK
        (length(btrim(reserve_idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_buyer_wallet_reservation_status CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT ck_buyer_wallet_reservation_terminal_time CHECK (
        (status = 'RESERVED' AND consumed_at IS NULL AND released_at IS NULL)
        OR (status = 'CONSUMED' AND consumed_at IS NOT NULL AND released_at IS NULL)
        OR (status = 'RELEASED' AND consumed_at IS NULL AND released_at IS NOT NULL)
    )
);

CREATE INDEX ix_buyer_wallet_reservation_scope_status
    ON payments.buyer_wallet_reservation (tenant_id, workspace_id, buyer_identity_id, status, created_at);

CREATE TABLE payments.buyer_wallet_ledger_entry (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_identity_id UUID NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'PEN',
    reservation_id UUID,
    entry_type VARCHAR(32) NOT NULL,
    amount_delta NUMERIC(19,4) NOT NULL,
    source_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    provider_code VARCHAR(32),
    provider_event_id VARCHAR(160),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_buyer_wallet_ledger_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_wallet_ledger_source UNIQUE
        (tenant_id, workspace_id, buyer_identity_id, entry_type, source_id),
    CONSTRAINT uq_buyer_wallet_ledger_idempotency UNIQUE
        (tenant_id, workspace_id, buyer_identity_id, idempotency_key),
    CONSTRAINT fk_buyer_wallet_ledger_account FOREIGN KEY
        (tenant_id, workspace_id, buyer_identity_id, currency)
        REFERENCES payments.buyer_wallet_account
        (tenant_id, workspace_id, buyer_identity_id, currency) ON DELETE RESTRICT,
    CONSTRAINT fk_buyer_wallet_ledger_reservation FOREIGN KEY (tenant_id, workspace_id, reservation_id)
        REFERENCES payments.buyer_wallet_reservation (tenant_id, workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_buyer_wallet_ledger_currency CHECK (currency = 'PEN'),
    CONSTRAINT ck_buyer_wallet_ledger_idempotency CHECK
        (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_buyer_wallet_ledger_entry CHECK (
        (entry_type = 'PROVIDER_RECHARGE' AND amount_delta > 0 AND reservation_id IS NULL
            AND provider_code IS NOT NULL AND provider_event_id IS NOT NULL)
        OR (entry_type = 'ORDER_CONSUMPTION' AND amount_delta < 0 AND reservation_id IS NOT NULL
            AND provider_code IS NULL AND provider_event_id IS NULL)
        OR (entry_type = 'REFUND' AND amount_delta > 0 AND reservation_id IS NOT NULL
            AND provider_code IS NULL AND provider_event_id IS NULL)
    )
);

CREATE UNIQUE INDEX uq_buyer_wallet_provider_event
    ON payments.buyer_wallet_ledger_entry (tenant_id, workspace_id, provider_code, provider_event_id)
    WHERE provider_event_id IS NOT NULL;
CREATE INDEX ix_buyer_wallet_ledger_history
    ON payments.buyer_wallet_ledger_entry (tenant_id, workspace_id, buyer_identity_id, occurred_at, id);

CREATE TABLE payments.buyer_wallet_reservation_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_identity_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_buyer_wallet_reservation_event_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_wallet_reservation_event_idempotency UNIQUE
        (tenant_id, workspace_id, buyer_identity_id, idempotency_key),
    CONSTRAINT uq_buyer_wallet_reservation_event_type UNIQUE
        (tenant_id, workspace_id, reservation_id, event_type),
    CONSTRAINT fk_buyer_wallet_reservation_event_reservation FOREIGN KEY
        (tenant_id, workspace_id, reservation_id)
        REFERENCES payments.buyer_wallet_reservation (tenant_id, workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_buyer_wallet_reservation_event_type CHECK
        (event_type IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT ck_buyer_wallet_reservation_event_amount CHECK (amount > 0),
    CONSTRAINT ck_buyer_wallet_reservation_event_idempotency CHECK
        (length(btrim(idempotency_key)) BETWEEN 1 AND 160)
);

CREATE INDEX ix_buyer_wallet_reservation_event_history
    ON payments.buyer_wallet_reservation_event (tenant_id, workspace_id, reservation_id, occurred_at, id);

CREATE FUNCTION payments.reject_buyer_wallet_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Buyer wallet ledger and reservation events are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER buyer_wallet_ledger_append_only
    BEFORE UPDATE OR DELETE ON payments.buyer_wallet_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION payments.reject_buyer_wallet_history_mutation();
CREATE TRIGGER buyer_wallet_reservation_event_append_only
    BEFORE UPDATE OR DELETE ON payments.buyer_wallet_reservation_event
    FOR EACH ROW EXECUTE FUNCTION payments.reject_buyer_wallet_history_mutation();

CREATE FUNCTION payments.guard_buyer_wallet_reservation_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Buyer wallet reservations cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.id, NEW.tenant_id, NEW.workspace_id, NEW.buyer_identity_id, NEW.currency,
           NEW.sales_order_id, NEW.amount, NEW.reserve_idempotency_key, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.tenant_id, OLD.workspace_id, OLD.buyer_identity_id, OLD.currency,
           OLD.sales_order_id, OLD.amount, OLD.reserve_idempotency_key, OLD.created_at)
       OR OLD.status <> 'RESERVED'
       OR NEW.status NOT IN ('CONSUMED', 'RELEASED') THEN
        RAISE EXCEPTION 'Buyer wallet reservation permits one immutable terminal transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER buyer_wallet_reservation_transition_guard
    BEFORE UPDATE OR DELETE ON payments.buyer_wallet_reservation
    FOR EACH ROW EXECUTE FUNCTION payments.guard_buyer_wallet_reservation_transition();

ALTER TABLE payments.buyer_wallet_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_account FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_reservation FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_ledger_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_ledger_entry FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_reservation_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.buyer_wallet_reservation_event FORCE ROW LEVEL SECURITY;

CREATE POLICY buyer_wallet_account_tenant_workspace_scope ON payments.buyer_wallet_account
    USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''));
CREATE POLICY buyer_wallet_reservation_tenant_workspace_scope ON payments.buyer_wallet_reservation
    USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''));
CREATE POLICY buyer_wallet_ledger_tenant_workspace_scope ON payments.buyer_wallet_ledger_entry
    USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''));
CREATE POLICY buyer_wallet_reservation_event_tenant_workspace_scope ON payments.buyer_wallet_reservation_event
    USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''));

REVOKE ALL PRIVILEGES ON payments.buyer_wallet_account,
    payments.buyer_wallet_reservation, payments.buyer_wallet_ledger_entry,
    payments.buyer_wallet_reservation_event FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        REVOKE ALL PRIVILEGES ON payments.buyer_wallet_account,
            payments.buyer_wallet_reservation, payments.buyer_wallet_ledger_entry,
            payments.buyer_wallet_reservation_event FROM nexa_runtime;
        GRANT SELECT ON payments.buyer_wallet_account TO nexa_runtime;
        GRANT INSERT (id, tenant_id, workspace_id, buyer_identity_id, currency)
            ON payments.buyer_wallet_account TO nexa_runtime;
        GRANT UPDATE (posted_balance, reserved_balance, version, updated_at)
            ON payments.buyer_wallet_account TO nexa_runtime;
        GRANT SELECT ON payments.buyer_wallet_reservation TO nexa_runtime;
        GRANT INSERT (id, tenant_id, workspace_id, buyer_identity_id, currency, sales_order_id,
                      amount, reserve_idempotency_key, created_at)
            ON payments.buyer_wallet_reservation TO nexa_runtime;
        GRANT UPDATE (status, updated_at, consumed_at, released_at)
            ON payments.buyer_wallet_reservation TO nexa_runtime;
        GRANT SELECT, INSERT ON payments.buyer_wallet_ledger_entry TO nexa_runtime;
        GRANT SELECT, INSERT ON payments.buyer_wallet_reservation_event TO nexa_runtime;
    END IF;
END;
$$;
