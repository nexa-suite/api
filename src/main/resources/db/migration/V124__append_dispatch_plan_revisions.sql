ALTER TABLE logistics.fulfillment_driver_assignment
    DROP CONSTRAINT uq_fulfillment_driver_assignment_fulfillment,
    ADD COLUMN planned_dispatch_at TIMESTAMPTZ,
    ADD CONSTRAINT uq_fulfillment_driver_assignment_version
        UNIQUE (tenant_id, workspace_id, fulfillment_id, fulfillment_version);

CREATE INDEX ix_fulfillment_driver_assignment_history
    ON logistics.fulfillment_driver_assignment
       (tenant_id, workspace_id, fulfillment_id, fulfillment_version DESC);
