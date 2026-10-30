DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_payments_worker') THEN
        RAISE EXCEPTION 'nexa_payments_worker role must be provisioned before Tenant migration V42';
    END IF;
END
$$;

-- Callback authorization checks the live Buyer-to-account relationship and
-- account lifecycle before saving a provider method. Keep this read limited to
-- the identifiers and lifecycle columns required by that check.
GRANT SELECT (tenant_id, workspace_id, workspace_membership_id, client_account_id, status)
    ON TABLE sales.client_account_membership TO nexa_payments_worker;
GRANT SELECT (id, tenant_id, workspace_id, status)
    ON TABLE sales.client_account TO nexa_payments_worker;

-- Preserve the normal Tenant/Workspace policies for other roles while narrowing
-- this worker's relationship reads to the Buyer installed by the callback router.
CREATE POLICY payments_worker_buyer_membership_scope
    ON sales.client_account_membership
    AS RESTRICTIVE
    FOR SELECT
    TO nexa_payments_worker
    USING (workspace_membership_id = NULLIF(current_setting('app.current_membership_id', true), '')::uuid);

CREATE POLICY payments_worker_buyer_account_scope
    ON sales.client_account
    AS RESTRICTIVE
    FOR SELECT
    TO nexa_payments_worker
    USING (EXISTS (
        SELECT 1
        FROM sales.client_account_membership member
        WHERE member.tenant_id = client_account.tenant_id
          AND member.workspace_id = client_account.workspace_id
          AND member.client_account_id = client_account.id
          AND member.workspace_membership_id = NULLIF(current_setting('app.current_membership_id', true), '')::uuid
          AND member.status = 'ACTIVE'
    ));

DO $$
BEGIN
    IF NOT has_column_privilege('nexa_payments_worker',
            'sales.client_account_membership', 'workspace_membership_id', 'SELECT')
        OR NOT has_column_privilege('nexa_payments_worker',
            'sales.client_account_membership', 'client_account_id', 'SELECT')
        OR NOT has_column_privilege('nexa_payments_worker',
            'sales.client_account_membership', 'status', 'SELECT')
        OR NOT has_column_privilege('nexa_payments_worker',
            'sales.client_account', 'status', 'SELECT') THEN
        RAISE EXCEPTION 'Tenant V42 Buyer payment callback read grants are incomplete';
    END IF;
END
$$;
