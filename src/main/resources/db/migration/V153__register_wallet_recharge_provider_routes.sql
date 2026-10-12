-- Central control-plane locator for verified PSP events. It contains only
-- routing/correlation metadata; Buyer balances and recharge facts stay Tenant-local.
ALTER TABLE tenant_management.tenant_business_database_binding
    ADD COLUMN wallet_recharge_callback_credential_secret_reference VARCHAR(512),
    ADD CONSTRAINT ck_tenant_business_database_binding_wallet_recharge_secret
        CHECK (wallet_recharge_callback_credential_secret_reference IS NULL
            OR length(btrim(wallet_recharge_callback_credential_secret_reference)) > 0),
    ADD CONSTRAINT uq_tenant_business_database_binding_tenant_identity
        UNIQUE (tenant_id, database_identity);

CREATE UNIQUE INDEX uq_tenant_business_database_binding_wallet_recharge_secret
    ON tenant_management.tenant_business_database_binding
    (wallet_recharge_callback_credential_secret_reference)
    WHERE wallet_recharge_callback_credential_secret_reference IS NOT NULL;

COMMENT ON COLUMN tenant_management.tenant_business_database_binding.wallet_recharge_callback_credential_secret_reference IS
    'Opaque reference to the isolated Tenant-local wallet-recharge callback credential; secret bytes are never stored centrally.';

ALTER TABLE payments.stripe_event_inbox
    ADD COLUMN wallet_recharge_id UUID;

CREATE TABLE tenant_management.wallet_recharge_provider_route (
    recharge_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    database_identity UUID NOT NULL,
    provider_code VARCHAR(16) NOT NULL DEFAULT 'STRIPE',
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'PEN',
    provider_payment_intent_id VARCHAR(255),
    status VARCHAR(24) NOT NULL DEFAULT 'PREPARING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_wallet_recharge_provider_route_scope_id
        UNIQUE (tenant_id, workspace_id, recharge_id),
    CONSTRAINT fk_wallet_recharge_provider_route_scope
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_wallet_recharge_provider_route_binding
        FOREIGN KEY (tenant_id, database_identity)
        REFERENCES tenant_management.tenant_business_database_binding (tenant_id, database_identity)
        ON DELETE RESTRICT,
    CONSTRAINT ck_wallet_recharge_provider_route_provider CHECK (provider_code = 'STRIPE'),
    CONSTRAINT ck_wallet_recharge_provider_route_amount
        CHECK (amount_minor BETWEEN 1 AND 99999999),
    CONSTRAINT ck_wallet_recharge_provider_route_currency CHECK (currency = 'PEN'),
    CONSTRAINT ck_wallet_recharge_provider_route_status
        CHECK (status IN ('PREPARING', 'AWAITING_PAYMENT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_wallet_recharge_provider_route_terminal_time CHECK (
        (status IN ('PREPARING', 'AWAITING_PAYMENT') AND processed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED') AND processed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_wallet_recharge_provider_route_intent
    ON tenant_management.wallet_recharge_provider_route (provider_code, provider_payment_intent_id)
    WHERE provider_payment_intent_id IS NOT NULL;
CREATE INDEX ix_wallet_recharge_provider_route_scope_status
    ON tenant_management.wallet_recharge_provider_route (tenant_id, workspace_id, status, created_at);

ALTER TABLE tenant_management.wallet_recharge_provider_route ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.wallet_recharge_provider_route FORCE ROW LEVEL SECURITY;
CREATE POLICY wallet_recharge_provider_route_tenant_workspace_scope
    ON tenant_management.wallet_recharge_provider_route
    USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))
    WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''));

REVOKE ALL PRIVILEGES ON tenant_management.wallet_recharge_provider_route FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON tenant_management.wallet_recharge_provider_route TO nexa_runtime;
    END IF;
END;
$$;
