ALTER TABLE sales.purchase_request
    ADD COLUMN IF NOT EXISTS delivery_address_snapshot JSONB,
    ADD COLUMN IF NOT EXISTS route_snapshot JSONB,
    ADD COLUMN IF NOT EXISTS warehouse_selection_snapshot JSONB;

ALTER TABLE sales.purchase_request
    DROP CONSTRAINT IF EXISTS ck_purchase_request_payment_option;

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_payment_option_v1
        CHECK (payment_option IS NULL OR payment_option IN ('CREDIT_LINE', 'BANK_TRANSFER', 'CARD_STRIPE', 'CASH', 'CASH_ON_DELIVERY'));

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_delivery_snapshot_size
        CHECK (delivery_address_snapshot IS NULL OR octet_length(delivery_address_snapshot::text) <= 32000),
    ADD CONSTRAINT ck_purchase_request_route_snapshot_size
        CHECK (route_snapshot IS NULL OR octet_length(route_snapshot::text) <= 32000),
    ADD CONSTRAINT ck_purchase_request_warehouse_snapshot_size
        CHECK (warehouse_selection_snapshot IS NULL OR octet_length(warehouse_selection_snapshot::text) <= 32000);

ALTER TABLE business_documents.business_document
    ADD CONSTRAINT fk_business_document_generated_by
        FOREIGN KEY (workspace_id, generated_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

ALTER TABLE business_documents.document_generation_request
    ADD CONSTRAINT fk_document_generation_requested_by
        FOREIGN KEY (workspace_id, requested_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);
