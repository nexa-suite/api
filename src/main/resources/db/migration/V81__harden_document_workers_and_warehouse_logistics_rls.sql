-- V81 is the security-worker migration for this checkout. Integration reported
-- a parallel V79; the WIP files currently present are
-- V79__add_delivery_attempts_and_continuations.sql and
-- V80__add_safety_stock_and_inventory_transfers.sql. They remain untouched
-- for integration to resolve.
-- This migration is additive and does not alter authentication or introduce an actor.

ALTER TABLE business_documents.document_generation_request
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

ALTER TABLE business_documents.evidence_object
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID,
    ADD COLUMN IF NOT EXISTS upload_lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS upload_claim_token UUID;

-- Existing in-flight rows are made immediately retryable. New workers must
-- acquire an explicit token before touching the external storage boundary.
UPDATE business_documents.document_generation_request
SET lease_until = current_timestamp
WHERE status = 'PROCESSING' AND lease_until IS NULL;

UPDATE business_documents.evidence_object
SET lease_until = current_timestamp
WHERE lifecycle_status = 'SCANNING' AND lease_until IS NULL;

UPDATE business_documents.evidence_object
SET upload_lease_until = current_timestamp
WHERE lifecycle_status = 'UPLOADING' AND upload_lease_until IS NULL;

CREATE INDEX IF NOT EXISTS ix_document_generation_claim_queue
    ON business_documents.document_generation_request (status, lease_until, next_attempt_at, requested_at, id);

CREATE INDEX IF NOT EXISTS ix_evidence_claim_queue
    ON business_documents.evidence_object
        (lifecycle_status, lease_until, upload_lease_until, next_scan_at, created_at, id);

-- Only tables with direct tenant/workspace columns are protected here. Child
-- tables that inherit scope solely through a parent remain unchanged until a
-- separate additive scope migration can preserve their existing contracts.
DO $$
DECLARE
    target regclass;
    policy_name TEXT;
    entry TEXT;
BEGIN
    FOREACH entry IN ARRAY ARRAY[
        'warehouse.warehouse|warehouse_tenant_workspace_scope',
        'warehouse.storage_zone|storage_zone_tenant_workspace_scope',
        'warehouse.inventory_lot|inventory_lot_tenant_workspace_scope',
        'warehouse.stock_movement|stock_movement_tenant_workspace_scope',
        'warehouse.inventory_event|inventory_event_tenant_workspace_scope',
        'warehouse.inventory_reservation|inventory_reservation_tenant_workspace_scope',
        'warehouse.command_idempotency|warehouse_command_idempotency_tenant_workspace_scope',
        'warehouse.warehouse_service_configuration|warehouse_service_configuration_tenant_workspace_scope',
        'warehouse.selection_snapshot|selection_snapshot_tenant_workspace_scope',
        'logistics.dispatch_number_counter|dispatch_number_counter_tenant_workspace_scope',
        'logistics.dispatch_order|dispatch_order_tenant_workspace_scope',
        'logistics.dispatch_event|dispatch_event_tenant_workspace_scope',
        'logistics.command_idempotency|logistics_command_idempotency_tenant_workspace_scope',
        'logistics.proof_of_delivery|proof_of_delivery_tenant_workspace_scope',
        'logistics.temperature_reading|temperature_reading_tenant_workspace_scope',
        'logistics.delivery_incident|delivery_incident_tenant_workspace_scope',
        'logistics.operational_handoff_note|operational_handoff_note_tenant_workspace_scope'
    ] LOOP
        target := split_part(entry, '|', 1)::regclass;
        policy_name := split_part(entry, '|', 2);
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', policy_name, target);
        EXECUTE format(
            'CREATE POLICY %I ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))',
            policy_name, target
        );
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    END LOOP;
END;
$$;
