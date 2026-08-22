-- Delivery outcomes are additive extensions of the existing dispatch-backed
-- Delivery. Historical migrations remain immutable.
-- This checkout uses V79__add_delivery_attempts_and_continuations.sql.
-- Integration note: a parallel WIP migration occupied V79 during implementation;
-- verify the final Flyway version allocation before applying this migration.

-- Expand the existing status constraint for the new closed partial outcome.
ALTER TABLE logistics.dispatch_order
    DROP CONSTRAINT IF EXISTS ck_dispatch_status;

ALTER TABLE logistics.dispatch_order
    ADD CONSTRAINT ck_dispatch_status CHECK (status IN ('READY_FOR_OPERATIONS','PREPARING','ASSIGNED','SCHEDULED',
        'READY_FOR_ROUTE','IN_ROUTE','PARTIAL','DELIVERED','INCIDENT','REPROGRAMMED','CANCELLED'));

CREATE TABLE logistics.delivery_attempt (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(2000),
    notes VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_delivery_attempt_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_delivery_attempt_number UNIQUE (tenant_id, workspace_id, delivery_id, attempt_number),
    CONSTRAINT fk_delivery_attempt_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.dispatch_order (tenant_id, workspace_id, id),
    CONSTRAINT ck_delivery_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_delivery_attempt_status CHECK (status IN ('FAILED', 'PARTIAL', 'FINAL')),
    CONSTRAINT ck_delivery_attempt_failure_reason CHECK (
        status <> 'FAILED' OR (failure_reason IS NOT NULL AND length(trim(failure_reason)) > 0)
    )
);

CREATE TABLE logistics.delivery_attempt_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_attempt_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_delivery_attempt_line_item UNIQUE (tenant_id, workspace_id, delivery_attempt_id, catalog_item_id),
    CONSTRAINT fk_delivery_attempt_line_attempt FOREIGN KEY (tenant_id, workspace_id, delivery_attempt_id)
        REFERENCES logistics.delivery_attempt (tenant_id, workspace_id, id),
    CONSTRAINT ck_delivery_attempt_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_delivery_attempt_line_catalog_item CHECK (length(trim(catalog_item_id)) > 0),
    CONSTRAINT ck_delivery_attempt_line_unit CHECK (length(trim(unit)) > 0)
);

CREATE TABLE logistics.continuation_delivery (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    source_delivery_id UUID NOT NULL,
    sales_order_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_continuation_delivery_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_continuation_delivery_source UNIQUE (tenant_id, workspace_id, source_delivery_id),
    CONSTRAINT fk_continuation_delivery_source FOREIGN KEY (tenant_id, workspace_id, source_delivery_id)
        REFERENCES logistics.dispatch_order (tenant_id, workspace_id, id),
    CONSTRAINT fk_continuation_delivery_order FOREIGN KEY (tenant_id, workspace_id, sales_order_id)
        REFERENCES sales.sales_order (tenant_id, workspace_id, id),
    CONSTRAINT fk_continuation_delivery_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT ck_continuation_delivery_status CHECK (status IN ('OPEN', 'FULFILLED', 'CANCELLED')),
    CONSTRAINT ck_continuation_delivery_version CHECK (version >= 0)
);

CREATE TABLE logistics.continuation_delivery_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    continuation_delivery_id UUID NOT NULL,
    catalog_item_id VARCHAR(64) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_continuation_delivery_line_item UNIQUE (tenant_id, workspace_id, continuation_delivery_id, catalog_item_id),
    CONSTRAINT fk_continuation_delivery_line_parent FOREIGN KEY (tenant_id, workspace_id, continuation_delivery_id)
        REFERENCES logistics.continuation_delivery (tenant_id, workspace_id, id),
    CONSTRAINT ck_continuation_delivery_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_continuation_delivery_line_catalog_item CHECK (length(trim(catalog_item_id)) > 0),
    CONSTRAINT ck_continuation_delivery_line_unit CHECK (length(trim(unit)) > 0)
);

CREATE INDEX ix_delivery_attempt_delivery_time
    ON logistics.delivery_attempt (tenant_id, workspace_id, delivery_id, attempt_number DESC, occurred_at DESC, id);
CREATE INDEX ix_delivery_attempt_line_attempt
    ON logistics.delivery_attempt_line (tenant_id, workspace_id, delivery_attempt_id, catalog_item_id);
CREATE INDEX ix_continuation_delivery_order
    ON logistics.continuation_delivery (tenant_id, workspace_id, sales_order_id, status, created_at DESC, id);
CREATE INDEX ix_continuation_delivery_line_parent
    ON logistics.continuation_delivery_line (tenant_id, workspace_id, continuation_delivery_id, catalog_item_id);

CREATE TRIGGER logistics_delivery_attempt_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_attempt FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_delivery_attempt_line_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_attempt_line FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_continuation_delivery_line_append_only
    BEFORE UPDATE OR DELETE ON logistics.continuation_delivery_line FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE OR REPLACE FUNCTION logistics.prevent_non_final_pod_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM logistics.dispatch_order d
        WHERE d.tenant_id = NEW.tenant_id
          AND d.workspace_id = NEW.workspace_id
          AND d.id = NEW.dispatch_order_id
          AND d.status = 'DELIVERED'
    ) THEN
        RAISE EXCEPTION 'Proof of delivery is only allowed for final delivery';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER logistics_pod_only_final_delivery
    BEFORE INSERT ON logistics.proof_of_delivery FOR EACH ROW
    EXECUTE FUNCTION logistics.prevent_non_final_pod_insert();

DO $$
DECLARE
    target regclass;
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'logistics.delivery_attempt',
        'logistics.delivery_attempt_line',
        'logistics.continuation_delivery',
        'logistics.continuation_delivery_line'
    ] LOOP
        target := table_name::regclass;
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
        EXECUTE format('DROP POLICY IF EXISTS logistics_tenant_workspace_scope ON %s', target);
        EXECUTE format(
            'CREATE POLICY logistics_tenant_workspace_scope ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))',
            target
        );
    END LOOP;
END;
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT USAGE ON SCHEMA logistics TO nexa_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
            logistics.delivery_attempt,
            logistics.delivery_attempt_line,
            logistics.continuation_delivery,
            logistics.continuation_delivery_line TO nexa_runtime;
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA logistics GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexa_runtime';
    END IF;
END;
$$;
