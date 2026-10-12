-- The central control plane stores opaque callback routing metadata only.
-- Payment and receivable facts remain in the Tenant business database.
ALTER TABLE tenant_management.tenant_business_database_binding
    ADD COLUMN payment_callback_credential_secret_reference VARCHAR(512),
    ADD CONSTRAINT ck_tenant_business_database_binding_payment_secret
        CHECK (payment_callback_credential_secret_reference IS NULL
            OR length(btrim(payment_callback_credential_secret_reference)) > 0);

CREATE UNIQUE INDEX uq_tenant_business_database_binding_payment_secret
    ON tenant_management.tenant_business_database_binding
    (payment_callback_credential_secret_reference)
    WHERE payment_callback_credential_secret_reference IS NOT NULL;

COMMENT ON COLUMN tenant_management.tenant_business_database_binding.payment_callback_credential_secret_reference IS
    'Opaque reference to the isolated Tenant-local Payments callback credential; secret bytes are never stored centrally.';

ALTER TABLE payments.stripe_event_inbox
    ADD COLUMN payment_route_id UUID,
    ADD CONSTRAINT ck_stripe_event_payment_route_scope
        CHECK (payment_route_id IS NULL OR (tenant_id IS NULL AND workspace_id IS NULL
            AND wallet_recharge_id IS NULL));

CREATE TABLE tenant_management.payment_provider_route (
    route_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    database_identity UUID NOT NULL,
    payment_id UUID NOT NULL,
    provider_code VARCHAR(16) NOT NULL DEFAULT 'STRIPE',
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    provider_payment_intent_id VARCHAR(255),
    status VARCHAR(24) NOT NULL DEFAULT 'PREPARING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_payment_provider_route_payment UNIQUE (tenant_id, workspace_id, payment_id),
    CONSTRAINT fk_payment_provider_route_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_provider_route_binding FOREIGN KEY (tenant_id, database_identity)
        REFERENCES tenant_management.tenant_business_database_binding (tenant_id, database_identity)
        ON DELETE RESTRICT,
    CONSTRAINT ck_payment_provider_route_provider CHECK (provider_code = 'STRIPE'),
    CONSTRAINT ck_payment_provider_route_amount CHECK (amount_minor BETWEEN 1 AND 9999999999),
    CONSTRAINT ck_payment_provider_route_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_provider_route_status CHECK
        (status IN ('PREPARING','AWAITING_PAYMENT','SUCCEEDED','FAILED','CANCELLED','REJECTED')),
    CONSTRAINT ck_payment_provider_route_terminal_time CHECK
        ((status IN ('PREPARING','AWAITING_PAYMENT') AND processed_at IS NULL)
            OR (status IN ('SUCCEEDED','FAILED','CANCELLED','REJECTED') AND processed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_payment_provider_route_intent
    ON tenant_management.payment_provider_route (provider_code, provider_payment_intent_id)
    WHERE provider_payment_intent_id IS NOT NULL;
CREATE INDEX ix_payment_provider_route_scope_status
    ON tenant_management.payment_provider_route (tenant_id, workspace_id, status, created_at);
CREATE INDEX ix_stripe_event_payment_route_queue
    ON payments.stripe_event_inbox (payment_route_id, status, next_attempt_at, received_at, event_id)
    WHERE payment_route_id IS NOT NULL;

REVOKE ALL PRIVILEGES ON tenant_management.payment_provider_route FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON tenant_management.payment_provider_route TO nexa_runtime;
    END IF;
END;
$$;
