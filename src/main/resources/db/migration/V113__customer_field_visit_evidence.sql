CREATE TABLE sales.field_visit_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    client_account_id uuid NOT NULL,
    recorded_by_membership_id uuid NOT NULL,
    customer_version bigint NOT NULL CHECK (customer_version >= 0),
    purpose varchar(500) NOT NULL,
    outcome varchar(2000) NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    UNIQUE (tenant_id,workspace_id,recorded_by_membership_id,idempotency_key)
);
CREATE INDEX field_visit_customer_history ON sales.field_visit_evidence(tenant_id,workspace_id,client_account_id,recorded_at DESC);
ALTER TABLE sales.field_visit_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.field_visit_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY field_visit_scope ON sales.field_visit_evidence
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON sales.field_visit_evidence TO nexa_runtime;
    END IF;
END; $$;
