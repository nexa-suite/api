CREATE SCHEMA integration;

CREATE TABLE integration.change_event (
    "sequence" BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_version BIGINT,
    public_status VARCHAR(48),
    audiences TEXT[] NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT ck_change_event_audiences CHECK (
        cardinality(audiences) > 0
        AND audiences <@ ARRAY['OWNER','SALES','WAREHOUSE','LOGISTICS','BUYER']::TEXT[]
    ),
    CONSTRAINT fk_change_event_scope_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_change_event_scope_account
        FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id)
);

CREATE INDEX ix_change_event_scope_sequence ON integration.change_event (tenant_id, workspace_id, "sequence");
CREATE INDEX ix_change_event_buyer_scope ON integration.change_event (tenant_id, workspace_id, client_account_id, "sequence");
CREATE INDEX ix_change_event_audiences ON integration.change_event USING GIN (audiences);
CREATE INDEX ix_change_event_expires_at ON integration.change_event (expires_at);
CREATE INDEX ix_change_event_aggregate ON integration.change_event (aggregate_type, aggregate_id);

CREATE TRIGGER change_event_append_only
    BEFORE UPDATE OR DELETE ON integration.change_event
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
