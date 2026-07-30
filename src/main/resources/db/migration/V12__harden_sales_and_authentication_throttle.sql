ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_priority
        CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT'));

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_payment_option
        CHECK (payment_option IS NULL OR payment_option IN ('CREDIT_LINE', 'BANK_TRANSFER', 'CASH', 'CASH_ON_DELIVERY'));

CREATE INDEX ix_authentication_failure_last_failure ON iam.authentication_failure (last_failure_at);
CREATE INDEX ix_purchase_request_scope_updated ON sales.purchase_request (tenant_id, workspace_id, updated_at DESC);
CREATE INDEX ix_purchase_request_line_request_created ON sales.purchase_request_line (purchase_request_id, created_at, id);

CREATE OR REPLACE FUNCTION sales.prevent_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Append-only record cannot be changed';
END;
$$;

CREATE TRIGGER purchase_request_event_append_only
    BEFORE UPDATE OR DELETE ON sales.purchase_request_event
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TRIGGER idempotency_record_append_only
    BEFORE UPDATE OR DELETE ON sales.idempotency_record
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
