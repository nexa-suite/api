-- PostgreSQL regex character classes avoid string-literal backslash ambiguity
-- while preserving the required major.minor snapshot contract.
ALTER TABLE sales.purchase_request_draft
    DROP CONSTRAINT IF EXISTS ck_purchase_request_draft_schema,
    ADD CONSTRAINT ck_purchase_request_draft_schema
        CHECK (snapshot_schema_version ~ '^[0-9]+[.][0-9]+$');

ALTER TABLE sales.purchase_request_draft_destination
    DROP CONSTRAINT IF EXISTS ck_purchase_request_draft_destination_schema,
    ADD CONSTRAINT ck_purchase_request_draft_destination_schema
        CHECK (snapshot_schema_version ~ '^[0-9]+[.][0-9]+$');

ALTER TABLE sales.purchase_request_draft_route
    DROP CONSTRAINT IF EXISTS ck_purchase_request_draft_route_schema,
    ADD CONSTRAINT ck_purchase_request_draft_route_schema
        CHECK (snapshot_schema_version ~ '^[0-9]+[.][0-9]+$');

ALTER TABLE sales.purchase_request_draft_warehouse_selection
    DROP CONSTRAINT IF EXISTS ck_purchase_request_draft_selection_schema,
    ADD CONSTRAINT ck_purchase_request_draft_selection_schema
        CHECK (snapshot_schema_version ~ '^[0-9]+[.][0-9]+$');
