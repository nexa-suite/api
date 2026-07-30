CREATE SCHEMA integration;

CREATE TABLE integration.change_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_change_event_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT fk_change_event_scope_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_change_event_scope_account
        FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id)
);

CREATE INDEX ix_change_event_scope_id ON integration.change_event (tenant_id, workspace_id, id);
CREATE INDEX ix_change_event_buyer_scope ON integration.change_event (tenant_id, workspace_id, client_account_id, id);

CREATE TRIGGER change_event_append_only
    BEFORE UPDATE OR DELETE ON integration.change_event
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
