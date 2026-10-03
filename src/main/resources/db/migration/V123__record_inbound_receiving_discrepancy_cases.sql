CREATE TABLE warehouse.inbound_receiving_discrepancy_case (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    expected_sku_id UUID,
    observed_sku_id UUID NOT NULL,
    expected_batch_reference VARCHAR(160),
    observed_batch_reference VARCHAR(160),
    expected_quantity NUMERIC(19,4) NOT NULL,
    observed_quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    observation_notes VARCHAR(2000),
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    recorded_by_membership_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    create_idempotency_key VARCHAR(160) NOT NULL,
    create_request_hash VARCHAR(64) NOT NULL,
    evidence_object_id UUID,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    submit_idempotency_key VARCHAR(160),
    submit_request_hash VARCHAR(64),
    CONSTRAINT uq_inbound_discrepancy_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_inbound_discrepancy_create_key UNIQUE (tenant_id, workspace_id, recorded_by_membership_id, create_idempotency_key),
    CONSTRAINT uq_inbound_discrepancy_submit_key UNIQUE (tenant_id, workspace_id, submitted_by_membership_id, submit_idempotency_key),
    CONSTRAINT fk_inbound_discrepancy_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_inbound_discrepancy_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id),
    CONSTRAINT fk_inbound_discrepancy_expected_sku FOREIGN KEY (tenant_id, workspace_id, expected_sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT fk_inbound_discrepancy_observed_sku FOREIGN KEY (tenant_id, workspace_id, observed_sku_id)
        REFERENCES catalog_management.sellable_sku (tenant_id, workspace_id, id),
    CONSTRAINT fk_inbound_discrepancy_recorded_by FOREIGN KEY (workspace_id, recorded_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_inbound_discrepancy_submitted_by FOREIGN KEY (workspace_id, submitted_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_inbound_discrepancy_quantities CHECK (expected_quantity >= 0 AND observed_quantity >= 0),
    CONSTRAINT ck_inbound_discrepancy_text CHECK (
        length(btrim(unit)) BETWEEN 1 AND 32 AND upper(btrim(unit)) = unit
        AND length(btrim(reason)) BETWEEN 1 AND 2000
        AND (expected_batch_reference IS NULL OR length(btrim(expected_batch_reference)) BETWEEN 1 AND 160)
        AND (observed_batch_reference IS NULL OR length(btrim(observed_batch_reference)) BETWEEN 1 AND 160)
        AND (observation_notes IS NULL OR length(btrim(observation_notes)) BETWEEN 1 AND 2000)),
    CONSTRAINT ck_inbound_discrepancy_hash CHECK (
        create_request_hash ~ '^[0-9a-f]{64}$'
        AND (submit_request_hash IS NULL OR submit_request_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_inbound_discrepancy_transition CHECK (
        (status = 'PENDING_EVIDENCE' AND version = 0 AND evidence_object_id IS NULL
            AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submit_idempotency_key IS NULL AND submit_request_hash IS NULL)
        OR (status = 'READY_FOR_REVIEW' AND version = 1 AND evidence_object_id IS NOT NULL
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND submit_idempotency_key IS NOT NULL AND submit_request_hash IS NOT NULL)),
    CONSTRAINT ck_inbound_discrepancy_time CHECK (recorded_at <= coalesce(submitted_at, recorded_at))
);

CREATE INDEX ix_inbound_discrepancy_warehouse_time
    ON warehouse.inbound_receiving_discrepancy_case (tenant_id, workspace_id, warehouse_id, recorded_at DESC, id DESC);

CREATE FUNCTION warehouse.guard_inbound_discrepancy_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'inbound receiving discrepancy facts are append-only';
    END IF;
    IF ROW(NEW.id, NEW.tenant_id, NEW.workspace_id, NEW.warehouse_id, NEW.expected_sku_id, NEW.observed_sku_id,
           NEW.expected_batch_reference, NEW.observed_batch_reference, NEW.expected_quantity, NEW.observed_quantity,
           NEW.unit, NEW.reason, NEW.observation_notes, NEW.recorded_by_membership_id, NEW.recorded_at,
           NEW.create_idempotency_key, NEW.create_request_hash)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.tenant_id, OLD.workspace_id, OLD.warehouse_id, OLD.expected_sku_id, OLD.observed_sku_id,
           OLD.expected_batch_reference, OLD.observed_batch_reference, OLD.expected_quantity, OLD.observed_quantity,
           OLD.unit, OLD.reason, OLD.observation_notes, OLD.recorded_by_membership_id, OLD.recorded_at,
           OLD.create_idempotency_key, OLD.create_request_hash) THEN
        RAISE EXCEPTION 'inbound receiving discrepancy observations are immutable';
    END IF;
    IF NOT (OLD.status = 'PENDING_EVIDENCE' AND NEW.status = 'READY_FOR_REVIEW'
            AND OLD.version = 0 AND NEW.version = 1 AND OLD.evidence_object_id IS NULL
            AND NEW.evidence_object_id IS NOT NULL AND OLD.submitted_by_membership_id IS NULL
            AND NEW.submitted_by_membership_id IS NOT NULL AND OLD.submitted_at IS NULL
            AND NEW.submitted_at IS NOT NULL AND OLD.submit_idempotency_key IS NULL
            AND NEW.submit_idempotency_key IS NOT NULL AND OLD.submit_request_hash IS NULL
            AND NEW.submit_request_hash IS NOT NULL) THEN
        RAISE EXCEPTION 'invalid inbound receiving discrepancy transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inbound_discrepancy_immutable_observation
    BEFORE UPDATE OR DELETE ON warehouse.inbound_receiving_discrepancy_case
    FOR EACH ROW EXECUTE FUNCTION warehouse.guard_inbound_discrepancy_mutation();

ALTER TABLE warehouse.inbound_receiving_discrepancy_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inbound_receiving_discrepancy_case FORCE ROW LEVEL SECURITY;
CREATE POLICY inbound_receiving_discrepancy_scope
    ON warehouse.inbound_receiving_discrepancy_case
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON warehouse.inbound_receiving_discrepancy_case TO nexa_runtime;
    END IF;
END;
$$;
