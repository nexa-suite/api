CREATE TABLE logistics.delivery_load (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    origin_warehouse_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    assigned_driver_membership_id UUID,
    vehicle_reference VARCHAR(120),
    assigned_by_membership_id UUID,
    assigned_at TIMESTAMPTZ,
    capacity_sufficient BOOLEAN NOT NULL,
    handling_compatible BOOLEAN NOT NULL,
    zone_reasonable BOOLEAN NOT NULL,
    no_exclusive_transport_restriction BOOLEAN NOT NULL,
    attested_by_membership_id UUID NOT NULL,
    attested_at TIMESTAMPTZ NOT NULL,
    attestation_observation VARCHAR(1000),
    offered_by_membership_id UUID,
    offered_at TIMESTAMPTZ,
    dispatch_confirmed_by_membership_id UUID,
    dispatch_confirmed_at TIMESTAMPTZ,
    driver_accepted_by_membership_id UUID,
    driver_accepted_at TIMESTAMPTZ,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_delivery_load_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_load_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_delivery_load_status CHECK (status IN ('DRAFT','ASSIGNED','OFFERED',
        'HANDOFF_CONFIRMED','DRIVER_ACCEPTED','RESPONSIBILITY_TRANSFERRED')),
    CONSTRAINT ck_delivery_load_version CHECK (version >= 0),
    CONSTRAINT ck_delivery_load_vehicle CHECK (vehicle_reference IS NULL OR length(btrim(vehicle_reference)) BETWEEN 1 AND 120),
    CONSTRAINT ck_delivery_load_attestation CHECK (
        capacity_sufficient AND handling_compatible AND zone_reasonable AND no_exclusive_transport_restriction
        AND length(btrim(attestation_observation)) <= 1000
    ),
    CONSTRAINT ck_delivery_load_assignment CHECK (
        (status = 'DRAFT' AND assigned_driver_membership_id IS NULL AND assigned_by_membership_id IS NULL AND assigned_at IS NULL)
        OR (status <> 'DRAFT' AND assigned_driver_membership_id IS NOT NULL
            AND assigned_by_membership_id IS NOT NULL AND assigned_at IS NOT NULL)
    ),
    CONSTRAINT ck_delivery_load_offer CHECK (
        (status IN ('DRAFT','ASSIGNED') AND offered_by_membership_id IS NULL AND offered_at IS NULL)
        OR (status IN ('OFFERED','HANDOFF_CONFIRMED','DRIVER_ACCEPTED','RESPONSIBILITY_TRANSFERRED')
            AND offered_by_membership_id IS NOT NULL AND offered_at IS NOT NULL)
    ),
    CONSTRAINT ck_delivery_load_dispatch_confirmation CHECK (
        (status IN ('DRAFT','ASSIGNED','OFFERED','DRIVER_ACCEPTED')
            AND dispatch_confirmed_by_membership_id IS NULL AND dispatch_confirmed_at IS NULL)
        OR (status IN ('HANDOFF_CONFIRMED','RESPONSIBILITY_TRANSFERRED')
            AND dispatch_confirmed_by_membership_id IS NOT NULL AND dispatch_confirmed_at IS NOT NULL)
    ),
    CONSTRAINT ck_delivery_load_driver_acceptance CHECK (
        (status IN ('DRAFT','ASSIGNED','OFFERED','HANDOFF_CONFIRMED')
            AND driver_accepted_by_membership_id IS NULL AND driver_accepted_at IS NULL)
        OR (status IN ('DRIVER_ACCEPTED','RESPONSIBILITY_TRANSFERRED')
            AND driver_accepted_by_membership_id IS NOT NULL AND driver_accepted_at IS NOT NULL)
    ),
    CONSTRAINT ck_delivery_load_transferred CHECK (
        (status = 'RESPONSIBILITY_TRANSFERRED') =
        (dispatch_confirmed_at IS NOT NULL AND driver_accepted_at IS NOT NULL)
    )
);

CREATE TABLE logistics.delivery_load_stop (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    load_id UUID NOT NULL,
    fulfillment_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    position INTEGER NOT NULL,
    delivery_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_delivery_load_stop_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_delivery_load_stop_position UNIQUE (tenant_id, workspace_id, load_id, position),
    CONSTRAINT uq_delivery_load_stop_fulfillment UNIQUE (tenant_id, workspace_id, fulfillment_id),
    CONSTRAINT uq_delivery_load_stop_delivery UNIQUE (tenant_id, workspace_id, delivery_id),
    CONSTRAINT fk_delivery_load_stop_parent FOREIGN KEY (tenant_id, workspace_id, load_id)
        REFERENCES logistics.delivery_load (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_load_stop_fulfillment FOREIGN KEY (tenant_id, workspace_id, fulfillment_id)
        REFERENCES logistics.fulfillment (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_load_stop_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT ck_delivery_load_stop_position CHECK (position BETWEEN 1 AND 20),
    CONSTRAINT ck_delivery_load_stop_version CHECK (delivery_version >= 0)
);

CREATE INDEX ix_delivery_load_current_scope ON logistics.delivery_load
    (tenant_id, workspace_id, status, updated_at DESC, id);
CREATE INDEX ix_delivery_load_driver_scope ON logistics.delivery_load
    (tenant_id, workspace_id, assigned_driver_membership_id, status, updated_at DESC, id);

CREATE TABLE logistics.delivery_load_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    load_id UUID NOT NULL,
    event_number BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    actor_membership_id UUID NOT NULL,
    affected_driver_membership_id UUID,
    vehicle_reference VARCHAR(120),
    reason VARCHAR(500),
    previous_stop_order JSONB NOT NULL DEFAULT '[]'::jsonb,
    new_stop_order JSONB NOT NULL DEFAULT '[]'::jsonb,
    capacity_sufficient BOOLEAN,
    handling_compatible BOOLEAN,
    zone_reasonable BOOLEAN,
    no_exclusive_transport_restriction BOOLEAN,
    attested_by_membership_id UUID,
    attested_at TIMESTAMPTZ,
    attestation_observation VARCHAR(1000),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_delivery_load_event_sequence UNIQUE (tenant_id, workspace_id, load_id, event_number),
    CONSTRAINT uq_delivery_load_event_scope_id UNIQUE (tenant_id, workspace_id, load_id, id),
    CONSTRAINT fk_delivery_load_event_parent FOREIGN KEY (tenant_id, workspace_id, load_id)
        REFERENCES logistics.delivery_load (tenant_id, workspace_id, id),
    CONSTRAINT ck_delivery_load_event_number CHECK (event_number >= 1),
    CONSTRAINT ck_delivery_load_event_type CHECK (event_type IN ('CREATED','STOPS_REORDERED','DRIVER_ASSIGNED',
        'OFFERED','HANDOFF_CONFIRMED','DRIVER_ACCEPTED')),
    CONSTRAINT ck_delivery_load_event_reason CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_delivery_load_event_attestation CHECK (
        (capacity_sufficient IS NULL AND handling_compatible IS NULL AND zone_reasonable IS NULL
            AND no_exclusive_transport_restriction IS NULL AND attested_by_membership_id IS NULL AND attested_at IS NULL)
        OR (capacity_sufficient AND handling_compatible AND zone_reasonable AND no_exclusive_transport_restriction
            AND attested_by_membership_id IS NOT NULL AND attested_at IS NOT NULL
            AND length(btrim(attestation_observation)) <= 1000)
    )
);

CREATE INDEX ix_delivery_load_event_history ON logistics.delivery_load_event
    (tenant_id, workspace_id, load_id, event_number);

CREATE TABLE logistics.delivery_load_command_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    operation VARCHAR(48) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_delivery_load_command_idempotency PRIMARY KEY
        (tenant_id, workspace_id, actor_membership_id, operation, idempotency_key),
    CONSTRAINT fk_delivery_load_command_idempotency_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_delivery_load_command_idempotency_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_delivery_load_command_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE TRIGGER logistics_delivery_load_event_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_load_event FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_delivery_load_command_idempotency_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_load_command_idempotency FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.delivery_load ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_load FORCE ROW LEVEL SECURITY;
CREATE POLICY v132_delivery_load_scope ON logistics.delivery_load
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE logistics.delivery_load_stop ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_load_stop FORCE ROW LEVEL SECURITY;
CREATE POLICY v132_delivery_load_stop_scope ON logistics.delivery_load_stop
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE logistics.delivery_load_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_load_event FORCE ROW LEVEL SECURITY;
CREATE POLICY v132_delivery_load_event_scope ON logistics.delivery_load_event
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE logistics.delivery_load_command_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_load_command_idempotency FORCE ROW LEVEL SECURITY;
CREATE POLICY v132_delivery_load_idempotency_scope ON logistics.delivery_load_command_idempotency
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT, UPDATE ON logistics.delivery_load, logistics.delivery_load_stop TO nexa_runtime;
        GRANT SELECT, INSERT ON logistics.delivery_load_event, logistics.delivery_load_command_idempotency TO nexa_runtime;
    END IF;
END;
$$;
