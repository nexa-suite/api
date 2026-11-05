-- BC-02 append-only plain-text communication between Buyer memberships and the
-- Sales membership assigned to their Client Account. Messages stay in the
-- routed Tenant business database; delivery is HTTP polling.

-- A Client Account can have several personal Buyer memberships. Keep the
-- one-account-per-Buyer uniqueness introduced in the baseline.
DROP INDEX sales.uq_client_account_one_buyer;

CREATE TABLE sales.buyer_sales_message (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    sender_membership_id UUID NOT NULL,
    sender_kind VARCHAR(8) NOT NULL,
    buyer_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_buyer_sales_message PRIMARY KEY (tenant_id, workspace_id, id),
    CONSTRAINT uq_buyer_sales_message_idempotency
        UNIQUE (tenant_id, workspace_id, sender_membership_id, idempotency_key),
    CONSTRAINT ck_buyer_sales_message_sender
        CHECK (sender_kind IN ('BUYER', 'SALES')
            AND (sender_kind <> 'BUYER' OR buyer_membership_id = sender_membership_id)),
    CONSTRAINT ck_buyer_sales_message_idempotency_key
        CHECK (char_length(btrim(idempotency_key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_buyer_sales_message_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_buyer_sales_message_body
        CHECK (char_length(btrim(body)) BETWEEN 1 AND 4000),
    CONSTRAINT fk_buyer_sales_message_scope_workspace
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id),
    CONSTRAINT fk_buyer_sales_message_scope_account
        FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT fk_buyer_sales_message_buyer_account_membership
        FOREIGN KEY (client_account_id, buyer_membership_id)
        REFERENCES sales.client_account_membership (client_account_id, workspace_membership_id)
);

CREATE INDEX ix_buyer_sales_message_account_order
    ON sales.buyer_sales_message (tenant_id, workspace_id, client_account_id, created_at, id);

CREATE INDEX ix_buyer_sales_message_buyer_order
    ON sales.buyer_sales_message (tenant_id, workspace_id, client_account_id, buyer_membership_id, created_at, id);

COMMENT ON TABLE sales.buyer_sales_message IS
    'BC-02 immutable plain-text messages scoped to one Tenant, Workspace, Buyer account, and assigned Sales relationship.';
COMMENT ON COLUMN sales.buyer_sales_message.sender_membership_id IS
    'Verified request actor membership. Buyer sends require this to equal the recipient; Sales assignment is enforced by the application against current account ownership.';
COMMENT ON COLUMN sales.buyer_sales_message.buyer_membership_id IS
    'Buyer membership that owns this personal view of the account thread; Sales and BOM views remain account-scoped.';
COMMENT ON COLUMN sales.buyer_sales_message.request_hash IS
    'SHA-256 of account, sender kind, actor, recipient Buyer membership, and exact body for idempotent retry validation.';

ALTER TABLE sales.buyer_sales_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.buyer_sales_message FORCE ROW LEVEL SECURITY;

CREATE POLICY buyer_sales_message_tenant_workspace_scope
    ON sales.buyer_sales_message
    FOR ALL
    USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    )
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    );

CREATE TRIGGER buyer_sales_message_append_only
    BEFORE UPDATE OR DELETE ON sales.buyer_sales_message
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        RAISE EXCEPTION 'nexa_runtime role must exist before Tenant migration V34';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA sales TO nexa_runtime;
REVOKE ALL PRIVILEGES ON TABLE sales.buyer_sales_message FROM PUBLIC, nexa_runtime;
GRANT SELECT, INSERT ON TABLE sales.buyer_sales_message TO nexa_runtime;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_class relation
        JOIN pg_namespace schema ON schema.oid = relation.relnamespace
        WHERE schema.nspname = 'sales'
          AND relation.relname = 'buyer_sales_message'
          AND relation.relrowsecurity
          AND relation.relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'Tenant V34 failed to force row security on sales.buyer_sales_message';
    END IF;

    IF has_table_privilege('nexa_runtime', 'sales.buyer_sales_message', 'UPDATE')
        OR has_table_privilege('nexa_runtime', 'sales.buyer_sales_message', 'DELETE')
        OR has_any_column_privilege('nexa_runtime', 'sales.buyer_sales_message', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V34 unexpectedly granted mutation or deletion of Buyer and Sales messages';
    END IF;
END
$$;
