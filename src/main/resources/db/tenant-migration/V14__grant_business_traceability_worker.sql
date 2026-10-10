DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'nexa_business_traceability_worker role must be provisioned before Tenant migration V14';
    END IF;

    EXECUTE 'GRANT USAGE ON SCHEMA nexa_platform, audit, integration, sales, payments, catalog_management, warehouse, logistics, business_documents TO nexa_business_traceability_worker';

    -- Router validates immutable physical identity and the exact Workspace anchor.
    EXECUTE 'GRANT SELECT (singleton, tenant_id, database_identity) ON TABLE nexa_platform.tenant_business_database_identity TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id) ON TABLE nexa_platform.tenant_workspace_scope_anchor TO nexa_business_traceability_worker';

    -- BC-11 source event replay, per-consumer receipts, audit projection and edge change feed.
    EXECUTE 'GRANT SELECT (event_id, event_type, aggregate_type, aggregate_id, tenant_id, workspace_id, occurred_at, correlation_id, causation_id, schema_version, payload, created_at) ON TABLE integration.outbox_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (event_id, event_type, aggregate_type, aggregate_id, tenant_id, workspace_id, occurred_at, correlation_id, causation_id, schema_version, payload) ON TABLE integration.outbox_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (consumer_name, event_id, tenant_id, workspace_id) ON TABLE integration.inbox_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (consumer_name, event_id, tenant_id, workspace_id, processed_at, result) ON TABLE integration.inbox_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, actor_membership_id, actor_work_area, event_type, subject_type, subject_id, correlation_id, safe_metadata, occurred_at) ON TABLE audit.event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (event_id, tenant_id, workspace_id, client_account_id, aggregate_type, aggregate_id, event_type, public_status, audiences, occurred_at, expires_at) ON TABLE integration.change_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE integration.change_event_sequence_seq TO nexa_business_traceability_worker';

    -- BC-02 tenant relationship snapshots used by approved wallet conversion,
    -- dispatch business-card lookup and notification recipient projection.
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, code, business_name, commercial_name, tax_country_code, tax_identifier_type, tax_identifier_value, segment, contact_person, contact_email, phone, delivery_profile, payment_condition, status, version, credit_currency, credit_limit, current_commercial_exposure) ON TABLE sales.client_account TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, client_account_id, workspace_membership_id) ON TABLE sales.client_account_membership TO nexa_business_traceability_worker';

    -- BC-04 approved Purchase Request conversion; human review stays outside this worker.
    EXECUTE 'GRANT SELECT, UPDATE ON TABLE sales.purchase_request TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE sales.purchase_request_line TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT ON TABLE sales.purchase_request_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, UPDATE ON TABLE sales.commercial_commitment TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE sales.sales_order TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE sales.sales_order_line TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT ON TABLE sales.sales_order_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE sales.sales_order_sequence TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE sales.idempotency_record TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE sales.idempotency_response TO nexa_business_traceability_worker';

    -- BC-08 wallet tender is called only within approved conversion and its owner transaction.
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, buyer_identity_id, currency, posted_balance, reserved_balance, version) ON TABLE payments.buyer_wallet_account TO nexa_business_traceability_worker';
    EXECUTE 'GRANT UPDATE (reserved_balance, version, updated_at) ON TABLE payments.buyer_wallet_account TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, buyer_identity_id, sales_order_id, amount, status) ON TABLE payments.buyer_wallet_reservation TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, buyer_identity_id, currency, sales_order_id, amount, reserve_idempotency_key, created_at) ON TABLE payments.buyer_wallet_reservation TO nexa_business_traceability_worker';
    EXECUTE 'GRANT UPDATE (status, updated_at, consumed_at) ON TABLE payments.buyer_wallet_reservation TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, buyer_identity_id, reservation_id, event_type, amount, idempotency_key) ON TABLE payments.buyer_wallet_reservation_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, buyer_identity_id, reservation_id, event_type, amount, idempotency_key, occurred_at) ON TABLE payments.buyer_wallet_reservation_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, buyer_identity_id, currency, reservation_id, entry_type, amount_delta, source_id, idempotency_key) ON TABLE payments.buyer_wallet_ledger_entry TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT (id, tenant_id, workspace_id, buyer_identity_id, currency, reservation_id, entry_type, amount_delta, source_id, idempotency_key, occurred_at) ON TABLE payments.buyer_wallet_ledger_entry TO nexa_business_traceability_worker';

    -- BC-07 Credit and Receivables effects invoked by conversion and INVOICE_ISSUED.
    EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE payments.credit_account TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE payments.credit_reservation TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE payments.receivable TO nexa_business_traceability_worker';

    -- BC-05 existing Sales Order reservation callback.
    EXECUTE 'GRANT SELECT ON TABLE catalog_management.sellable_sku TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.fulfillment TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE warehouse.command_idempotency TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE warehouse.inventory_reservation TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE warehouse.inventory_reservation_line TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE warehouse.inventory_reservation_allocation TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT ON TABLE warehouse.reservation_shortage TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, UPDATE ON TABLE warehouse.inventory_lot TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE warehouse.inventory_lot_disposition TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE warehouse.inventory_temperature_evaluation TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE warehouse.safety_stock_policy TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE warehouse.storage_zone TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE warehouse.warehouse TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT ON TABLE warehouse.stock_movement TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT ON TABLE warehouse.inventory_event TO nexa_business_traceability_worker';

    -- BC-06 FULFILLMENT_READY dispatch callback and its read-back projection.
    EXECUTE 'GRANT SELECT, INSERT ON TABLE logistics.dispatch_order TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE logistics.delivery TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE logistics.dispatch_number_counter TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE logistics.command_idempotency TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT ON TABLE logistics.dispatch_event TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.proof_of_delivery TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.delivery_attempt TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.delivery_attempt_line TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.continuation_delivery TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.continuation_delivery_line TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.customer_instruction_revision TO nexa_business_traceability_worker';
    EXECUTE 'GRANT INSERT ON TABLE logistics.delivery_instruction_revision TO nexa_business_traceability_worker';

    -- BC-09 commercial document requests after DISPATCH_DELIVERED.
    EXECUTE 'GRANT SELECT, INSERT ON TABLE business_documents.business_document TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT, INSERT ON TABLE business_documents.document_generation_request TO nexa_business_traceability_worker';
    EXECUTE 'GRANT SELECT ON TABLE logistics.temperature_reading TO nexa_business_traceability_worker';
END
$$;
