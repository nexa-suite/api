CREATE INDEX ix_sales_order_buyer_scope_created
    ON sales.sales_order (tenant_id, workspace_id, client_account_id, created_at DESC, id);
