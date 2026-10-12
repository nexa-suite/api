DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_documents_worker') THEN
        RAISE EXCEPTION 'nexa_business_documents_worker role must be provisioned before Tenant migration V12';
    END IF;
    EXECUTE 'GRANT USAGE ON SCHEMA nexa_platform, business_documents, sales, catalog_management, logistics, payments, integration TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (singleton, tenant_id, database_identity) ON TABLE nexa_platform.tenant_business_database_identity TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id) ON TABLE nexa_platform.tenant_workspace_scope_anchor TO nexa_business_documents_worker';

    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, subject_type, subject_id, document_type, format, status, replacement_of_document_id) ON TABLE business_documents.business_document TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (id, document_id, tenant_id, workspace_id, subject_type, subject_id, document_type, format, status, attempt_count, requested_at, next_attempt_at, lease_until, claim_token) ON TABLE business_documents.document_generation_request TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, subject_type, subject_id, object_key, lifecycle_status, declared_content_type, original_filename, scan_attempt_count, next_scan_at, lease_until, created_at) ON TABLE business_documents.evidence_object TO nexa_business_documents_worker';
    EXECUTE 'GRANT UPDATE (status, updated_at, failure_code, failure_detail, storage_object_key, checksum_sha256, content_type, byte_size, generated_at) ON TABLE business_documents.business_document TO nexa_business_documents_worker';
    EXECUTE 'GRANT UPDATE (status, attempt_count, processing_started_at, lease_until, claim_token, next_attempt_at, completed_at, last_error) ON TABLE business_documents.document_generation_request TO nexa_business_documents_worker';
    EXECUTE 'GRANT UPDATE (lifecycle_status, scan_attempt_count, claim_token, lease_until, updated_at, detected_content_type, failure_code, scanned_at, next_scan_at) ON TABLE business_documents.evidence_object TO nexa_business_documents_worker';
    EXECUTE 'GRANT INSERT (object_key, tenant_id, workspace_id, bucket_name, checksum_sha256, content_type, byte_size, private_object, created_at) ON TABLE business_documents.object_storage_object TO nexa_business_documents_worker';

    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, client_account_id, number, created_at, requested_delivery_date, status, delivery_snapshot, delivery_address_snapshot, route_snapshot, warehouse_selection_snapshot, payment_option, notes, currency, total_amount) ON TABLE sales.sales_order TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (sales_order_id, created_at, id, sku_id, product_family_id, sku_code_snapshot, product_family_code_snapshot, catalog_item_id, item_name_snapshot, presentation_snapshot, quantity, unit, unit_price_amount, line_subtotal, unit_price_currency) ON TABLE sales.sales_order_line TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, client_account_id, code, created_at, requested_delivery_date, status, payment_option, comments, review_note, delivery_address_snapshot, route_snapshot, warehouse_selection_snapshot) ON TABLE sales.purchase_request TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (purchase_request_id, created_at, id, sku_id, product_family_id, sku_code_snapshot, product_family_code_snapshot, catalog_item_id, item_name_snapshot, presentation_snapshot, quantity, unit, unit_price_amount, unit_price_currency) ON TABLE sales.purchase_request_line TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, code, business_name, commercial_name, tax_identifier_type, tax_identifier_value, segment, status) ON TABLE sales.client_account TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, client_account_id) ON TABLE sales.client_account_membership TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, family_id, sku_code, gross_weight) ON TABLE catalog_management.sellable_sku TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, name) ON TABLE catalog_management.product_family TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, client_account_id, receivable_id, amount, currency, method, status, provider_payment_intent_id, created_at) ON TABLE payments.payment TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (id, tenant_id, workspace_id, client_account_id, subject_type, subject_id, receivable_number, currency, amount, amount_paid, adjustment_total, status, due_at, version, created_at) ON TABLE payments.receivable TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, payment_id, amount) ON TABLE payments.receivable_allocation TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, client_account_id, dispatch_number, status, destination_snapshot, delivery_window_start, eta, responsible_display_name_snapshot, vehicle_reference, route_name, temperature_status, sales_order_id) ON TABLE logistics.dispatch_order TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, dispatch_order_id, receiver_name, completed_at, notes, status, photo_evidence_declared, signature_evidence_declared) ON TABLE logistics.proof_of_delivery TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, dispatch_order_id, incident_type, severity, description, occurred_at, resolution) ON TABLE logistics.delivery_incident TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, delivery_id) ON TABLE logistics.driver_delivery_incident TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, dispatch_order_id, fulfillment_id) ON TABLE logistics.delivery TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, id, sales_order_id) ON TABLE logistics.fulfillment TO nexa_business_documents_worker';
    EXECUTE 'GRANT SELECT (tenant_id, workspace_id, dispatch_order_id, value, unit, recorded_at) ON TABLE logistics.temperature_reading TO nexa_business_documents_worker';
    EXECUTE 'GRANT INSERT (event_id, event_type, aggregate_type, aggregate_id, tenant_id, workspace_id, occurred_at, correlation_id, causation_id, schema_version, payload) ON TABLE integration.outbox_event TO nexa_business_documents_worker';
END
$$;
