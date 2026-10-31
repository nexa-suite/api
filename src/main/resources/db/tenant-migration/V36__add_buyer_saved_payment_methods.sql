-- BC-08 stores only provider references and non-sensitive card summaries.
-- Card numbers, CVCs, cardholder data, and SetupIntent client secrets never enter Nexa.

CREATE UNIQUE INDEX uq_client_account_membership_buyer_account_scope
    ON sales.client_account_membership (tenant_id, workspace_id, workspace_membership_id, client_account_id);

CREATE TABLE payments.buyer_payment_provider_profile (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    provider_customer_id VARCHAR(255),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT pk_buyer_payment_provider_profile
        PRIMARY KEY (tenant_id, workspace_id, buyer_membership_id),
    CONSTRAINT uq_buyer_payment_provider_profile_scope_account
        UNIQUE (tenant_id, workspace_id, buyer_membership_id, client_account_id),
    CONSTRAINT uq_buyer_payment_provider_profile_customer
        UNIQUE (tenant_id, workspace_id, provider_customer_id),
    CONSTRAINT ck_buyer_payment_provider_profile_lock_version
        CHECK (lock_version >= 0),
    CONSTRAINT fk_buyer_payment_provider_profile_membership
        FOREIGN KEY (tenant_id, workspace_id, buyer_membership_id, client_account_id)
        REFERENCES sales.client_account_membership
            (tenant_id, workspace_id, workspace_membership_id, client_account_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_buyer_payment_provider_profile_workspace
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id)
);

CREATE TABLE payments.buyer_payment_method_setup_attempt (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    provider_customer_id VARCHAR(255),
    provider_setup_intent_id VARCHAR(255),
    status VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_buyer_payment_method_setup_attempt
        PRIMARY KEY (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_payment_method_setup_attempt_membership
        UNIQUE (tenant_id, workspace_id, id, buyer_membership_id),
    CONSTRAINT uq_buyer_payment_method_setup_attempt_id
        UNIQUE (id),
    CONSTRAINT uq_buyer_payment_method_setup_attempt_idempotency
        UNIQUE (tenant_id, workspace_id, buyer_membership_id, idempotency_key_hash),
    CONSTRAINT uq_buyer_payment_method_setup_attempt_provider_intent
        UNIQUE (tenant_id, workspace_id, provider_setup_intent_id),
    CONSTRAINT ck_buyer_payment_method_setup_attempt_key_hash
        CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_buyer_payment_method_setup_attempt_status
        CHECK (status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_buyer_payment_method_setup_attempt_completion
        CHECK ((status = 'SUCCEEDED' AND completed_at IS NOT NULL)
            OR (status <> 'SUCCEEDED' AND completed_at IS NULL)),
    CONSTRAINT fk_buyer_payment_method_setup_attempt_profile
        FOREIGN KEY (tenant_id, workspace_id, buyer_membership_id, client_account_id)
        REFERENCES payments.buyer_payment_provider_profile
            (tenant_id, workspace_id, buyer_membership_id, client_account_id)
        ON DELETE CASCADE
);

CREATE TABLE payments.buyer_saved_payment_method (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    provider_payment_method_id VARCHAR(255) NOT NULL,
    payment_type VARCHAR(24) NOT NULL,
    brand VARCHAR(40) NOT NULL,
    last4 CHAR(4) NOT NULL,
    expiry_month SMALLINT NOT NULL,
    expiry_year SMALLINT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT pk_buyer_saved_payment_method PRIMARY KEY (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_saved_payment_method_id UNIQUE (id),
    CONSTRAINT uq_buyer_saved_payment_method_provider_id
        UNIQUE (tenant_id, workspace_id, provider_payment_method_id),
    CONSTRAINT ck_buyer_saved_payment_method_type CHECK (payment_type = 'CARD'),
    CONSTRAINT ck_buyer_saved_payment_method_brand CHECK (length(brand) BETWEEN 1 AND 40),
    CONSTRAINT ck_buyer_saved_payment_method_last4 CHECK (last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_buyer_saved_payment_method_expiry_month CHECK (expiry_month BETWEEN 1 AND 12),
    CONSTRAINT ck_buyer_saved_payment_method_expiry_year CHECK (expiry_year BETWEEN 2000 AND 9999),
    CONSTRAINT fk_buyer_saved_payment_method_profile
        FOREIGN KEY (tenant_id, workspace_id, buyer_membership_id, client_account_id)
        REFERENCES payments.buyer_payment_provider_profile
            (tenant_id, workspace_id, buyer_membership_id, client_account_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_buyer_saved_payment_method_default_per_membership
    ON payments.buyer_saved_payment_method (tenant_id, workspace_id, buyer_membership_id)
    WHERE is_default;

CREATE INDEX ix_buyer_saved_payment_method_membership_created
    ON payments.buyer_saved_payment_method (tenant_id, workspace_id, buyer_membership_id, created_at DESC, id);

CREATE TABLE payments.buyer_payment_method_setup_event (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    buyer_membership_id UUID NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    setup_attempt_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    signature_sha256 CHAR(64) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_buyer_payment_method_setup_event PRIMARY KEY (tenant_id, workspace_id, event_id),
    CONSTRAINT ck_buyer_payment_method_setup_event_signature_hash
        CHECK (signature_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_buyer_payment_method_setup_event_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT fk_buyer_payment_method_setup_event_attempt
        FOREIGN KEY (tenant_id, workspace_id, setup_attempt_id, buyer_membership_id)
        REFERENCES payments.buyer_payment_method_setup_attempt
            (tenant_id, workspace_id, id, buyer_membership_id)
        ON DELETE CASCADE
);

CREATE INDEX ix_buyer_payment_method_setup_attempt_membership_created
    ON payments.buyer_payment_method_setup_attempt
        (tenant_id, workspace_id, buyer_membership_id, created_at DESC, id);

COMMENT ON TABLE payments.buyer_payment_provider_profile IS
    'Tenant-local Stripe Customer reference for one Buyer membership and its linked Client Account; contains no cardholder data.';
COMMENT ON TABLE payments.buyer_payment_method_setup_attempt IS
    'Idempotent server-issued SetupIntent attempts. A method is saved only after a verified provider webhook.';
COMMENT ON TABLE payments.buyer_saved_payment_method IS
    'Tenant-local tokenized provider method reference and safe card display summary; never PAN or CVC.';
COMMENT ON TABLE payments.buyer_payment_method_setup_event IS
    'Idempotency evidence for signed provider SetupIntent callbacks; stores only event identity and signature digest.';
COMMENT ON COLUMN payments.buyer_payment_provider_profile.lock_version IS
    'Dedicated least-privilege UPDATE grant target for worker SELECT FOR UPDATE; not business or provider identity state.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_payments_worker') THEN
        RAISE EXCEPTION 'Tenant runtime and Payments worker roles must exist before migration V36';
    END IF;
END
$$;

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'buyer_payment_provider_profile',
        'buyer_payment_method_setup_attempt',
        'buyer_saved_payment_method',
        'buyer_payment_method_setup_event'
    ] LOOP
        EXECUTE format('ALTER TABLE payments.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE payments.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON payments.%I FOR ALL USING ('
            || 'tenant_id::text = nullif(current_setting(''app.current_tenant_id'', true), '''') '
            || 'AND workspace_id::text = nullif(current_setting(''app.current_workspace_id'', true), '''') '
            || 'AND buyer_membership_id::text = nullif(current_setting(''app.current_membership_id'', true), '''')'
            || ') WITH CHECK ('
            || 'tenant_id::text = nullif(current_setting(''app.current_tenant_id'', true), '''') '
            || 'AND workspace_id::text = nullif(current_setting(''app.current_workspace_id'', true), '''') '
            || 'AND buyer_membership_id::text = nullif(current_setting(''app.current_membership_id'', true), '''')'
            || ')',
            'buyer_payment_membership_scope', table_name);
    END LOOP;
END
$$;

REVOKE ALL PRIVILEGES ON TABLE payments.buyer_payment_provider_profile,
    payments.buyer_payment_method_setup_attempt, payments.buyer_saved_payment_method,
    payments.buyer_payment_method_setup_event FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE payments.buyer_payment_provider_profile,
    payments.buyer_payment_method_setup_attempt TO nexa_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE payments.buyer_saved_payment_method TO nexa_runtime;
GRANT SELECT, INSERT ON TABLE payments.buyer_payment_method_setup_event TO nexa_payments_worker;
GRANT SELECT (tenant_id, workspace_id, buyer_membership_id, client_account_id, provider_customer_id, lock_version)
    ON TABLE payments.buyer_payment_provider_profile TO nexa_payments_worker;
GRANT UPDATE (lock_version) ON TABLE payments.buyer_payment_provider_profile TO nexa_payments_worker;
GRANT SELECT (id, tenant_id, workspace_id, buyer_membership_id, client_account_id,
        provider_customer_id, provider_setup_intent_id, status, completed_at)
    ON TABLE payments.buyer_payment_method_setup_attempt TO nexa_payments_worker;
GRANT UPDATE (status, completed_at) ON TABLE payments.buyer_payment_method_setup_attempt TO nexa_payments_worker;
GRANT SELECT (id, tenant_id, workspace_id, buyer_membership_id, client_account_id,
        provider_payment_method_id, payment_type, brand, last4, expiry_month, expiry_year, is_default, created_at)
    ON TABLE payments.buyer_saved_payment_method TO nexa_payments_worker;
GRANT INSERT (id, tenant_id, workspace_id, buyer_membership_id, client_account_id,
        provider_payment_method_id, payment_type, brand, last4, expiry_month, expiry_year, is_default, created_at)
    ON TABLE payments.buyer_saved_payment_method TO nexa_payments_worker;
GRANT UPDATE (is_default) ON TABLE payments.buyer_saved_payment_method TO nexa_payments_worker;
GRANT SELECT (tenant_id, workspace_id, event_id) ON TABLE payments.buyer_payment_method_setup_event TO nexa_payments_worker;

DO $$
BEGIN
    IF (SELECT count(*) FROM pg_policies
        WHERE schemaname='payments'
          AND tablename IN ('buyer_payment_provider_profile', 'buyer_payment_method_setup_attempt',
                            'buyer_saved_payment_method', 'buyer_payment_method_setup_event')
          AND policyname='buyer_payment_membership_scope') <> 4 THEN
        RAISE EXCEPTION 'Tenant V36 failed to install direct Buyer membership RLS policies';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_class relation
        JOIN pg_namespace schema ON schema.oid=relation.relnamespace
        WHERE schema.nspname='payments'
          AND relation.relname IN ('buyer_payment_provider_profile', 'buyer_payment_method_setup_attempt',
                                   'buyer_saved_payment_method', 'buyer_payment_method_setup_event')
          AND (NOT relation.relrowsecurity OR NOT relation.relforcerowsecurity)
    ) THEN
        RAISE EXCEPTION 'Tenant V36 failed to force row security for Buyer saved payment methods';
    END IF;
    IF NOT has_column_privilege('nexa_payments_worker',
            'payments.buyer_payment_provider_profile', 'lock_version', 'SELECT')
        OR NOT has_column_privilege('nexa_payments_worker',
            'payments.buyer_payment_provider_profile', 'lock_version', 'UPDATE')
        OR NOT has_column_privilege('nexa_payments_worker',
            'payments.buyer_saved_payment_method', 'is_default', 'UPDATE')
        OR NOT has_column_privilege('nexa_payments_worker',
            'payments.buyer_payment_method_setup_attempt', 'completed_at', 'SELECT')
        OR has_column_privilege('nexa_payments_worker',
            'payments.buyer_payment_method_setup_attempt', 'idempotency_key_hash', 'SELECT')
        OR has_column_privilege('nexa_payments_worker',
            'payments.buyer_payment_provider_profile', 'provider_customer_id', 'UPDATE')
        OR has_column_privilege('nexa_payments_worker',
            'payments.buyer_payment_provider_profile', 'buyer_membership_id', 'UPDATE')
        OR has_column_privilege('nexa_payments_worker',
            'payments.buyer_payment_provider_profile', 'client_account_id', 'UPDATE')
        OR has_column_privilege('nexa_payments_worker',
            'payments.buyer_saved_payment_method', 'provider_payment_method_id', 'UPDATE')
        OR has_column_privilege('nexa_payments_worker',
            'payments.buyer_saved_payment_method', 'buyer_membership_id', 'UPDATE')
        OR has_column_privilege('nexa_payments_worker',
            'payments.buyer_saved_payment_method', 'client_account_id', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V36 Buyer payment worker grants exceed the scoped lock/default columns';
    END IF;
END
$$;
