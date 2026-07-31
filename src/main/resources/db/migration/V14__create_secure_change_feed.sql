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

CREATE OR REPLACE FUNCTION integration.purge_expired_change_events(batch_size INTEGER)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, integration
AS $$
DECLARE
    deleted_count BIGINT;
BEGIN
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 1000 THEN
        RAISE EXCEPTION 'batch_size must be between 1 and 1000';
    END IF;
    PERFORM set_config('nexa.change_feed.maintenance', 'on', true);
    WITH expired AS (
        SELECT ctid FROM integration.change_event
        WHERE expires_at < current_timestamp
        ORDER BY expires_at, "sequence"
        LIMIT batch_size
    )
    DELETE FROM integration.change_event event
    USING expired
    WHERE event.ctid = expired.ctid;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

REVOKE UPDATE, DELETE ON integration.change_event FROM PUBLIC;
REVOKE ALL ON FUNCTION integration.purge_expired_change_events(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration.purge_expired_change_events(INTEGER) TO CURRENT_USER;

CREATE OR REPLACE FUNCTION sales.prevent_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_SCHEMA = 'integration'
        AND TG_TABLE_NAME = 'change_event'
        AND TG_OP = 'DELETE'
        AND current_setting('nexa.change_feed.maintenance', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Append-only record cannot be changed';
END;
$$;

CREATE TRIGGER change_event_append_only
    BEFORE UPDATE OR DELETE ON integration.change_event
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
