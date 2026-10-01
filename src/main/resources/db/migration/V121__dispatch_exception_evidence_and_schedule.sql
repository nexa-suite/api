CREATE TABLE logistics.fulfillment_outgoing_discrepancy_resolution (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    fulfillment_version BIGINT NOT NULL,
    physical_allocation_id UUID NOT NULL,
    physical_allocation_version BIGINT NOT NULL,
    discrepancy_check_id UUID NOT NULL,
    matching_check_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fulfillment_discrepancy_resolution_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_fulfillment_discrepancy_resolution_check UNIQUE (tenant_id, workspace_id, discrepancy_check_id),
    CONSTRAINT uq_fulfillment_discrepancy_resolution_key UNIQUE
        (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_fulfillment_discrepancy_resolution_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_fulfillment_discrepancy_resolution_fulfillment FOREIGN KEY
        (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT fk_fulfillment_discrepancy_resolution_allocation FOREIGN KEY
        (tenant_id, workspace_id, physical_allocation_id)
        REFERENCES warehouse.physical_allocation (tenant_id, workspace_id, id),
    CONSTRAINT fk_fulfillment_discrepancy_resolution_mismatch FOREIGN KEY
        (tenant_id, workspace_id, discrepancy_check_id)
        REFERENCES logistics.fulfillment_outgoing_goods_check (tenant_id, workspace_id, id),
    CONSTRAINT fk_fulfillment_discrepancy_resolution_match FOREIGN KEY
        (tenant_id, workspace_id, matching_check_id)
        REFERENCES logistics.fulfillment_outgoing_goods_check (tenant_id, workspace_id, id),
    CONSTRAINT ck_fulfillment_discrepancy_resolution_checks CHECK (discrepancy_check_id <> matching_check_id),
    CONSTRAINT ck_fulfillment_discrepancy_resolution_versions CHECK (physical_allocation_version >= 0),
    CONSTRAINT ck_fulfillment_discrepancy_resolution_key CHECK
        (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_fulfillment_discrepancy_resolution_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fulfillment_discrepancy_resolution_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 1000)
);

CREATE INDEX ix_fulfillment_discrepancy_resolution_work
    ON logistics.fulfillment_outgoing_discrepancy_resolution
       (tenant_id, workspace_id, fulfillment_id, physical_allocation_id, physical_allocation_version,
        resolved_at DESC);

CREATE TRIGGER logistics_fulfillment_discrepancy_resolution_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_outgoing_discrepancy_resolution FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.fulfillment_outgoing_discrepancy_resolution ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.fulfillment_outgoing_discrepancy_resolution FORCE ROW LEVEL SECURITY;
CREATE POLICY v121_fulfillment_discrepancy_resolution_scope
    ON logistics.fulfillment_outgoing_discrepancy_resolution
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON logistics.fulfillment_outgoing_discrepancy_resolution TO nexa_runtime;
    END IF;
END;
$$;
