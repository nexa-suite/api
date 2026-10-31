-- Buyer membership is a personal identity linked to a business Client Account.
-- Preserve the link row for audit, messaging, payment, and preference references.

ALTER TABLE sales.client_account_membership
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN individual_account_id UUID;

-- V3 enforced one Buyer per account. Business accounts may now hold multiple
-- Buyer memberships; the membership-side unique keys still prevent one Buyer
-- from being linked to multiple accounts.
-- V34 already removed the account-side unique index to enable account-scoped
-- Buyer conversations. Do not drop it a second time in this migration chain.

-- The legacy membership and account tables use FORCE RLS. Run the backfill
-- under each physical Tenant/Workspace scope so the restricted migrator sees
-- every row in this Tenant database instead of leaving updated_at NULL.
-- V3 restores row_security=off for the Flyway connection; keep policy checks on.
SET row_security = on;

DO $$
DECLARE
    workspace_scope RECORD;
BEGIN
    FOR workspace_scope IN
        SELECT anchor.tenant_id, anchor.workspace_id
          FROM nexa_platform.tenant_workspace_scope_anchor anchor
          JOIN nexa_platform.tenant_business_database_identity database_scope
            ON database_scope.tenant_id = anchor.tenant_id
         ORDER BY anchor.workspace_id
    LOOP
        PERFORM set_config('app.current_tenant_id', workspace_scope.tenant_id::TEXT, TRUE);
        PERFORM set_config('app.current_workspace_id', workspace_scope.workspace_id::TEXT, TRUE);

        UPDATE sales.client_account_membership member
           SET updated_at = member.created_at,
               individual_account_id = CASE
                   WHEN account.account_type = 'INDIVIDUAL' THEN member.client_account_id
               END
          FROM sales.client_account account
         WHERE account.tenant_id = member.tenant_id
           AND account.workspace_id = member.workspace_id
           AND account.id = member.client_account_id
           AND member.tenant_id = workspace_scope.tenant_id
           AND member.workspace_id = workspace_scope.workspace_id;
    END LOOP;
END
$$;

ALTER TABLE sales.client_account_membership
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT ck_client_account_membership_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    ADD CONSTRAINT ck_client_account_membership_version
        CHECK (version >= 0),
    ADD CONSTRAINT ck_client_account_membership_individual_account
        CHECK (individual_account_id IS NULL OR individual_account_id = client_account_id),
    ADD CONSTRAINT fk_client_account_membership_individual_account
        FOREIGN KEY (tenant_id, workspace_id, individual_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id);

CREATE UNIQUE INDEX uq_client_account_individual_buyer
    ON sales.client_account_membership (tenant_id, workspace_id, individual_account_id)
    WHERE individual_account_id IS NOT NULL;

CREATE OR REPLACE FUNCTION sales.enforce_client_account_membership_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    linked_account_type VARCHAR(16);
BEGIN
    IF TG_OP = 'UPDATE' AND ROW(
            OLD.client_account_id, OLD.workspace_membership_id, OLD.tenant_id, OLD.workspace_id,
            OLD.created_at, OLD.individual_account_id
        ) IS DISTINCT FROM ROW(
            NEW.client_account_id, NEW.workspace_membership_id, NEW.tenant_id, NEW.workspace_id,
            NEW.created_at, NEW.individual_account_id
        ) THEN
        RAISE EXCEPTION 'Buyer account membership identity is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_client_account_membership_identity_immutable';
    END IF;

    SELECT account.account_type
      INTO linked_account_type
      FROM sales.client_account account
     WHERE account.tenant_id = NEW.tenant_id
       AND account.workspace_id = NEW.workspace_id
       AND account.id = NEW.client_account_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Client Account for Buyer membership is not visible in the active Tenant and Workspace'
            USING ERRCODE = '23503', CONSTRAINT = 'fk_client_account_membership_scope_account';
    END IF;

    IF linked_account_type = 'INDIVIDUAL' THEN
        NEW.individual_account_id := NEW.client_account_id;
    ELSE
        NEW.individual_account_id := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER client_account_membership_identity_guard
    BEFORE INSERT OR UPDATE ON sales.client_account_membership
    FOR EACH ROW EXECUTE FUNCTION sales.enforce_client_account_membership_identity();

CREATE OR REPLACE FUNCTION sales.prevent_client_account_type_change_after_buyer_link()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_type IS DISTINCT FROM OLD.account_type
       AND EXISTS (
           SELECT 1
             FROM sales.client_account_membership member
            WHERE member.tenant_id = OLD.tenant_id
              AND member.workspace_id = OLD.workspace_id
              AND member.client_account_id = OLD.id
       ) THEN
        RAISE EXCEPTION 'Client Account type cannot change after Buyer membership is linked'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_client_account_type_immutable_after_buyer_link';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER client_account_type_membership_guard
    BEFORE UPDATE OF account_type ON sales.client_account
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_client_account_type_change_after_buyer_link();

COMMENT ON COLUMN sales.client_account_membership.status IS
    'Current Buyer-to-account access state. INACTIVE preserves the historical relationship row and dependent Buyer data.';
COMMENT ON COLUMN sales.client_account_membership.individual_account_id IS
    'Database-enforced cardinality key set only for INDIVIDUAL Client Accounts; one personal Buyer membership per account.';

REVOKE DELETE ON TABLE sales.client_account_membership FROM nexa_runtime;
REVOKE ALL PRIVILEGES ON FUNCTION sales.enforce_client_account_membership_identity() FROM PUBLIC;
REVOKE ALL PRIVILEGES ON FUNCTION sales.prevent_client_account_type_change_after_buyer_link() FROM PUBLIC;

DO $$
BEGIN
    IF has_table_privilege('nexa_runtime', 'sales.client_account_membership', 'DELETE') THEN
        RAISE EXCEPTION 'Tenant V37 must prevent runtime deletion of Buyer account membership history';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_trigger trigger
          JOIN pg_class relation ON relation.oid = trigger.tgrelid
          JOIN pg_namespace schema ON schema.oid = relation.relnamespace
         WHERE schema.nspname = 'sales'
           AND relation.relname = 'client_account_membership'
           AND trigger.tgname = 'client_account_membership_identity_guard'
           AND NOT trigger.tgisinternal
    ) THEN
        RAISE EXCEPTION 'Tenant V37 failed to install Buyer account membership identity guard';
    END IF;
END
$$;
