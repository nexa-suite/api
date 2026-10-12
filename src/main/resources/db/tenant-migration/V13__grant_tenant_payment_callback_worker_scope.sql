DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_payments_worker') THEN
        RAISE EXCEPTION 'nexa_payments_worker role must be provisioned before Tenant migration V13';
    END IF;

    EXECUTE 'GRANT USAGE ON SCHEMA nexa_platform, payments, sales, business_documents, audit, integration TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (singleton, tenant_id, database_identity) ON TABLE nexa_platform.tenant_business_database_identity TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id) ON TABLE nexa_platform.tenant_workspace_scope_anchor TO nexa_payments_worker';

    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, receivable_id, created_by_membership_id, status, amount, currency, provider_payment_intent_id, created_at, completed_at, client_account_id, version) ON TABLE payments.payment TO nexa_payments_worker';
    EXECUTE 'GRANT UPDATE (status, updated_at, completed_at, version) ON TABLE payments.payment TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (payment_id, attempt_number, status, provider_reference) ON TABLE payments.payment_attempt TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, payment_id, attempt_number, status, provider_reference, failure_code, created_at) ON TABLE payments.payment_attempt TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, payment_id, event_type, event_key, occurred_at) ON TABLE payments.payment_event TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, payment_id, receivable_id, sales_order_id, allocation_status, state, provider_refund_id, attempt_count, last_error, operator_note, created_at, updated_at, resolved_at, lease_until) ON TABLE payments.payment_reconciliation_case TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, payment_id, receivable_id, allocation_status, state, created_at, updated_at) ON TABLE payments.payment_reconciliation_case TO nexa_payments_worker';
    EXECUTE 'GRANT UPDATE (state, updated_at) ON TABLE payments.payment_reconciliation_case TO nexa_payments_worker';

    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, client_account_id, subject_type, subject_id, receivable_number, currency, amount, amount_paid, adjustment_total, status, due_at, version, created_at) ON TABLE payments.receivable TO nexa_payments_worker';
    EXECUTE 'GRANT UPDATE (amount_paid, status, updated_at, version) ON TABLE payments.receivable TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, receivable_id, payment_id, amount, currency) ON TABLE payments.receivable_application TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, receivable_id, payment_id, amount, currency, applied_at) ON TABLE payments.receivable_application TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, payment_id, receivable_id, amount) ON TABLE payments.receivable_allocation TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, receivable_id, payment_id, amount, allocated_at) ON TABLE payments.receivable_allocation TO nexa_payments_worker';

    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, number, client_account_id, status, payment_option, commercial_commitment_id, delivery_snapshot, currency, total_amount, version) ON TABLE sales.sales_order TO nexa_payments_worker';
    EXECUTE 'GRANT SELECT (id, sku_id, catalog_item_id, quantity, unit, unit_price_amount, unit_price_currency, sales_order_id) ON TABLE sales.sales_order_line TO nexa_payments_worker';

    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, subject_type, subject_id, document_type, format, version) ON TABLE business_documents.business_document TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, client_account_id, subject_type, subject_id, document_type, version, status, format, created_at, updated_at) ON TABLE business_documents.business_document TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, requested_by_membership_id, document_id, subject_type, subject_id, document_type, format, status, idempotency_key, request_hash, requested_at) ON TABLE business_documents.document_generation_request TO nexa_payments_worker';

    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, actor_membership_id, actor_work_area, event_type, subject_type, subject_id, correlation_id, safe_metadata, occurred_at) ON TABLE audit.event TO nexa_payments_worker';
    EXECUTE 'GRANT INSERT (event_id, event_type, aggregate_type, aggregate_id, tenant_id, workspace_id, occurred_at, correlation_id, causation_id, schema_version, payload) ON TABLE integration.outbox_event TO nexa_payments_worker';
END
$$;
