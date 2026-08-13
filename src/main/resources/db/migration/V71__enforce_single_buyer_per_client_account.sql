-- V1 exposes one buyer relationship on Client Account.  The persistence
-- contract is singular, so prevent multiple memberships from producing
-- ambiguous account projections or leaking a second relationship through a
-- crafted identifier.  V1-V70 remain immutable.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sales.client_account_membership
        GROUP BY tenant_id, workspace_id, client_account_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce one buyer relationship per client account while duplicates exist';
    END IF;
END;
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_client_account_one_buyer
    ON sales.client_account_membership (tenant_id, workspace_id, client_account_id);
