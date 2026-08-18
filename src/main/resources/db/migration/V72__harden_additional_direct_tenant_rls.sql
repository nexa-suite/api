-- These tables persist direct tenant/workspace scope and are accessed only
-- through scoped application flows.  Queue tables whose worker must claim
-- across tenants before propagating scope (document generation and Stripe
-- inbox), plus identity/authorization tables, remain explicit exceptions.
-- V1-V71 remain immutable.
DO $$
DECLARE
    target regclass;
    policy_name TEXT;
    entry TEXT;
BEGIN
    FOREACH entry IN ARRAY ARRAY[
        'payments.credit_reservation|credit_reservation_tenant_workspace_scope',
        'payments.payment_event|payment_event_tenant_workspace_scope',
        'sales.purchase_request_draft_line|purchase_request_draft_line_scope',
        'sales.purchase_request_draft_destination|purchase_request_draft_destination_scope',
        'sales.purchase_request_draft_route|purchase_request_draft_route_scope',
        'sales.purchase_request_draft_warehouse_selection|purchase_request_draft_warehouse_scope',
        'sales.purchase_request_draft_idempotency|purchase_request_draft_idempotency_scope'
    ] LOOP
        target := split_part(entry, '|', 1)::regclass;
        policy_name := split_part(entry, '|', 2);
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', policy_name, target);
        EXECUTE format(
            'CREATE POLICY %I ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))',
            policy_name, target
        );
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    END LOOP;
END;
$$;
