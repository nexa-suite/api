CREATE TABLE warehouse.physical_allocation_substitution_request (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    allocation_id UUID NOT NULL,
    physical_allocation_line_id UUID NOT NULL,
    expected_lot_id UUID NOT NULL,
    alternative_lot_id UUID NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    allocation_version BIGINT NOT NULL,
    actor_membership_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_physical_allocation_substitution_scope_id
        UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_physical_allocation_substitution_scope
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_physical_allocation_substitution_allocation
        FOREIGN KEY (tenant_id, workspace_id, allocation_id)
        REFERENCES warehouse.physical_allocation (tenant_id, workspace_id, id),
    CONSTRAINT fk_physical_allocation_substitution_line
        FOREIGN KEY (tenant_id, workspace_id, physical_allocation_line_id)
        REFERENCES warehouse.physical_allocation_line (tenant_id, workspace_id, id),
    CONSTRAINT fk_physical_allocation_substitution_expected_lot
        FOREIGN KEY (tenant_id, workspace_id, expected_lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, id),
    CONSTRAINT fk_physical_allocation_substitution_alternative_lot
        FOREIGN KEY (tenant_id, workspace_id, alternative_lot_id)
        REFERENCES warehouse.inventory_lot (tenant_id, workspace_id, id),
    CONSTRAINT fk_physical_allocation_substitution_actor
        FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_physical_allocation_substitution_lots
        CHECK (expected_lot_id <> alternative_lot_id),
    CONSTRAINT ck_physical_allocation_substitution_quantity
        CHECK (quantity > 0),
    CONSTRAINT ck_physical_allocation_substitution_text
        CHECK (length(btrim(unit)) BETWEEN 1 AND 32
            AND length(btrim(reason)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_physical_allocation_substitution_status
        CHECK (status = 'REQUESTED'),
    CONSTRAINT ck_physical_allocation_substitution_version
        CHECK (allocation_version >= 0)
);

CREATE INDEX ix_physical_allocation_substitution_request_time
    ON warehouse.physical_allocation_substitution_request
        (tenant_id, workspace_id, allocation_id, recorded_at DESC, id DESC);

CREATE TRIGGER warehouse_physical_allocation_substitution_request_append_only
    BEFORE UPDATE OR DELETE ON warehouse.physical_allocation_substitution_request
    FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE warehouse.physical_allocation_substitution_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.physical_allocation_substitution_request FORCE ROW LEVEL SECURITY;
CREATE POLICY physical_allocation_substitution_request_tenant_workspace_scope
    ON warehouse.physical_allocation_substitution_request
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON warehouse.physical_allocation_substitution_request TO nexa_runtime;
    END IF;
END;
$$;
