ALTER TABLE sales.client_account_membership ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS client_account_membership_tenant_workspace_scope ON sales.client_account_membership;
CREATE POLICY client_account_membership_tenant_workspace_scope ON sales.client_account_membership
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
        AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE sales.client_account_membership FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.manual_sales_order_draft FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.manual_sales_order_draft_line FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.manual_sales_order_draft_idempotency FORCE ROW LEVEL SECURITY;
