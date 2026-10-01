CREATE TABLE logistics.fulfillment_handoff_evidence (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    fulfillment_version BIGINT NOT NULL,
    warehouse_actor_membership_id UUID NOT NULL,
    driver_assignment_id UUID NOT NULL,
    driver_membership_id UUID NOT NULL,
    physical_allocation_id UUID NOT NULL,
    physical_allocation_version BIGINT NOT NULL,
    outgoing_goods_check_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fulfillment_handoff_evidence_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_fulfillment_handoff_evidence_fulfillment UNIQUE (tenant_id, workspace_id, fulfillment_id),
    CONSTRAINT uq_fulfillment_handoff_evidence_delivery UNIQUE (tenant_id, workspace_id, delivery_id),
    CONSTRAINT fk_fulfillment_handoff_evidence_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_fulfillment_handoff_evidence_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT fk_fulfillment_handoff_evidence_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT fk_fulfillment_handoff_evidence_assignment FOREIGN KEY (tenant_id, workspace_id, driver_assignment_id)
        REFERENCES logistics.fulfillment_driver_assignment (tenant_id, workspace_id, id),
    CONSTRAINT fk_fulfillment_handoff_evidence_check FOREIGN KEY (tenant_id, workspace_id, outgoing_goods_check_id)
        REFERENCES logistics.fulfillment_outgoing_goods_check (tenant_id, workspace_id, id),
    CONSTRAINT ck_fulfillment_handoff_evidence_versions CHECK
        (fulfillment_version >= 0 AND physical_allocation_version >= 0),
    CONSTRAINT ck_fulfillment_handoff_evidence_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_fulfillment_handoff_evidence_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TRIGGER logistics_fulfillment_handoff_evidence_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_handoff_evidence FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.fulfillment_handoff_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.fulfillment_handoff_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY v116_fulfillment_handoff_evidence_scope ON logistics.fulfillment_handoff_evidence
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON logistics.fulfillment_handoff_evidence TO nexa_runtime;
    END IF;
END;
$$;
