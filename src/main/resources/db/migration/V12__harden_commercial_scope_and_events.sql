ALTER TABLE tenant_management.workspace
    ADD CONSTRAINT uq_workspace_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE tenant_management.workspace_membership
    ADD CONSTRAINT uq_workspace_membership_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE sales.client_account
    ADD CONSTRAINT uq_client_account_scope_id UNIQUE (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_client_account_scope_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id);

ALTER TABLE sales.client_account_membership
    ADD CONSTRAINT fk_client_membership_scope_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    ADD CONSTRAINT fk_client_membership_scope_account
        FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_client_membership_scope_membership
        FOREIGN KEY (workspace_id, workspace_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT uq_purchase_request_scope_id UNIQUE (tenant_id, workspace_id, id),
    ADD CONSTRAINT ck_purchase_request_priority
        CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    ADD CONSTRAINT ck_purchase_request_payment_option
        CHECK (payment_option IS NULL OR payment_option IN ('CREDIT_LINE', 'BANK_TRANSFER', 'CASH', 'CASH_ON_DELIVERY')),
    ADD CONSTRAINT fk_purchase_request_scope_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    ADD CONSTRAINT fk_purchase_request_scope_account
        FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_purchase_request_scope_buyer
        FOREIGN KEY (workspace_id, buyer_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

ALTER TABLE sales.purchase_request_line
    DROP CONSTRAINT fk_purchase_request_line_request,
    ADD CONSTRAINT fk_purchase_request_line_request
        FOREIGN KEY (purchase_request_id) REFERENCES sales.purchase_request (id);

ALTER TABLE sales.purchase_request_event
    DROP CONSTRAINT fk_purchase_request_event_request,
    ADD CONSTRAINT fk_purchase_request_event_request
        FOREIGN KEY (tenant_id, workspace_id, purchase_request_id)
        REFERENCES sales.purchase_request (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_purchase_request_event_actor
        FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

ALTER TABLE sales.idempotency_record
    ADD CONSTRAINT fk_sales_idempotency_scope_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    ADD CONSTRAINT fk_sales_idempotency_actor
        FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id);

CREATE INDEX ix_authentication_failure_last_failure
    ON iam.authentication_failure (last_failure_at);

CREATE INDEX ix_purchase_request_scope_updated
    ON sales.purchase_request (tenant_id, workspace_id, updated_at DESC);

CREATE INDEX ix_purchase_request_line_request_created
    ON sales.purchase_request_line (purchase_request_id, created_at, id);

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
