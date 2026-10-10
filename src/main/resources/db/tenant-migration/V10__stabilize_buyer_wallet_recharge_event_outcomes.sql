-- Keep V9 immutable while aligning the durable outcome vocabulary with the
-- Tenant callback command contract and making cancellation replay stable.
ALTER TABLE payments.buyer_wallet_recharge_processed_event
    DROP CONSTRAINT ck_buyer_wallet_recharge_event_outcome;

ALTER TABLE payments.buyer_wallet_recharge_processed_event
    ADD CONSTRAINT ck_buyer_wallet_recharge_event_outcome
        CHECK (outcome IN ('APPLIED', 'PROCESSED', 'CANCELLED', 'IGNORED', 'REJECTED'));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_wallet_recharge_worker') THEN
        GRANT USAGE ON SCHEMA nexa_platform, payments TO nexa_wallet_recharge_worker;
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
