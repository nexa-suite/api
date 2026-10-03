CREATE TABLE logistics.fulfillment_outgoing_goods_check (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    fulfillment_version BIGINT NOT NULL,
    physical_allocation_id UUID NOT NULL,
    physical_allocation_version BIGINT NOT NULL,
    actor_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    matches BOOLEAN NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fulfillment_outgoing_goods_check_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_fulfillment_outgoing_goods_check_command UNIQUE
        (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_fulfillment_outgoing_goods_check_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_fulfillment_outgoing_goods_check_fulfillment FOREIGN KEY
        (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT ck_fulfillment_outgoing_goods_check_versions CHECK
        (fulfillment_version >= 0 AND physical_allocation_version >= 0),
    CONSTRAINT ck_fulfillment_outgoing_goods_check_key CHECK
        (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_fulfillment_outgoing_goods_check_hash CHECK
        (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE logistics.fulfillment_outgoing_goods_check_line (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    check_id UUID NOT NULL,
    physical_allocation_line_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    expected_lot_id UUID NOT NULL,
    observed_lot_id UUID,
    expected_quantity NUMERIC(19, 6) NOT NULL,
    observed_quantity NUMERIC(19, 6) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    matches BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, check_id, physical_allocation_line_id),
    CONSTRAINT fk_fulfillment_outgoing_goods_check_line_check FOREIGN KEY
        (tenant_id, workspace_id, check_id)
        REFERENCES logistics.fulfillment_outgoing_goods_check (tenant_id, workspace_id, id),
    CONSTRAINT ck_fulfillment_outgoing_goods_check_line_quantities CHECK
        (expected_quantity >= 0 AND observed_quantity >= 0),
    CONSTRAINT ck_fulfillment_outgoing_goods_check_line_unit CHECK
        (length(btrim(unit)) BETWEEN 1 AND 32)
);

CREATE INDEX ix_fulfillment_outgoing_goods_check_current
    ON logistics.fulfillment_outgoing_goods_check
        (tenant_id, workspace_id, fulfillment_id, physical_allocation_id,
         physical_allocation_version, checked_at DESC, id DESC);

CREATE TRIGGER logistics_fulfillment_outgoing_goods_check_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_outgoing_goods_check FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TRIGGER logistics_fulfillment_outgoing_goods_check_line_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_outgoing_goods_check_line FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.fulfillment_outgoing_goods_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.fulfillment_outgoing_goods_check FORCE ROW LEVEL SECURITY;
CREATE POLICY v114_fulfillment_outgoing_goods_check_scope
    ON logistics.fulfillment_outgoing_goods_check
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE logistics.fulfillment_outgoing_goods_check_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.fulfillment_outgoing_goods_check_line FORCE ROW LEVEL SECURITY;
CREATE POLICY v114_fulfillment_outgoing_goods_check_line_scope
    ON logistics.fulfillment_outgoing_goods_check_line
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON logistics.fulfillment_outgoing_goods_check TO nexa_runtime;
        GRANT SELECT, INSERT ON logistics.fulfillment_outgoing_goods_check_line TO nexa_runtime;
    END IF;
END;
$$;
