CREATE TABLE logistics.fulfillment_driver_assignment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    physical_allocation_id UUID NOT NULL,
    fulfillment_version BIGINT NOT NULL,
    physical_allocation_version BIGINT NOT NULL,
    responsible_membership_id UUID NOT NULL,
    responsible_user_id UUID NOT NULL,
    responsible_display_name_snapshot VARCHAR(160) NOT NULL,
    actor_membership_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fulfillment_driver_assignment_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_fulfillment_driver_assignment_fulfillment UNIQUE (tenant_id, workspace_id, fulfillment_id),
    CONSTRAINT fk_fulfillment_driver_assignment_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_fulfillment_driver_assignment_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT ck_fulfillment_driver_assignment_versions CHECK (fulfillment_version >= 0 AND physical_allocation_version >= 0),
    CONSTRAINT ck_fulfillment_driver_assignment_name CHECK (length(btrim(responsible_display_name_snapshot)) BETWEEN 1 AND 160)
);

CREATE TRIGGER logistics_fulfillment_driver_assignment_append_only
    BEFORE UPDATE OR DELETE ON logistics.fulfillment_driver_assignment FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.delivery_assignment
    ADD COLUMN fulfillment_driver_assignment_id UUID,
    ADD CONSTRAINT uq_delivery_assignment_source UNIQUE (tenant_id, workspace_id, fulfillment_driver_assignment_id),
    ADD CONSTRAINT fk_delivery_assignment_source FOREIGN KEY (tenant_id, workspace_id, fulfillment_driver_assignment_id)
        REFERENCES logistics.fulfillment_driver_assignment (tenant_id, workspace_id, id);

ALTER TABLE logistics.fulfillment_driver_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.fulfillment_driver_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY v111_fulfillment_driver_assignment_scope ON logistics.fulfillment_driver_assignment
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON logistics.fulfillment_driver_assignment TO nexa_runtime;
    END IF;
END;
$$;
