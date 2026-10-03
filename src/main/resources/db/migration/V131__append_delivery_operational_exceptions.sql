ALTER TABLE logistics.delivery_incident
    ADD COLUMN reported_by_membership_id UUID,
    ADD CONSTRAINT uq_delivery_incident_scope_dispatch_id
        UNIQUE (tenant_id, workspace_id, dispatch_order_id, id);

ALTER TABLE logistics.driver_delivery_incident
    ADD COLUMN incident_type VARCHAR(40),
    ADD COLUMN exception_severity VARCHAR(16),
    ADD CONSTRAINT uq_driver_delivery_incident_scope_delivery_id
        UNIQUE (tenant_id, workspace_id, delivery_id, id),
    ADD CONSTRAINT ck_driver_delivery_incident_classification CHECK (
        (incident_type IS NULL AND exception_severity IS NULL)
        OR (incident_type IN ('DELAY', 'INCOMPLETE_INSTRUCTION') AND exception_severity = 'WARNING')
        OR (incident_type IN ('ACCESS_BLOCKED', 'CUSTOMER_UNAVAILABLE', 'DELIVERY_NOT_EXECUTABLE')
            AND exception_severity = 'BLOCKING')
        OR (incident_type IN ('TEMPERATURE_EXCURSION', 'SAFETY_COMPROMISING_DAMAGE')
            AND exception_severity = 'CRITICAL')
    );

CREATE TABLE logistics.operational_exception_case (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    dispatch_order_id UUID,
    source_incident_id UUID NOT NULL,
    source_driver_incident_id UUID,
    type VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    reason VARCHAR(500),
    description VARCHAR(2000) NOT NULL,
    place VARCHAR(500),
    resolution VARCHAR(2000),
    outcome VARCHAR(2000),
    reported_by_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_operational_exception_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_operational_exception_delivery_id UNIQUE (tenant_id, workspace_id, delivery_id, id),
    CONSTRAINT uq_operational_exception_source UNIQUE
        (tenant_id, workspace_id, delivery_id, source_incident_id),
    CONSTRAINT fk_operational_exception_delivery
        FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT fk_operational_exception_dispatch_source
        FOREIGN KEY (tenant_id, workspace_id, dispatch_order_id, source_incident_id)
        REFERENCES logistics.delivery_incident (tenant_id, workspace_id, dispatch_order_id, id),
    CONSTRAINT fk_operational_exception_driver_source
        FOREIGN KEY (tenant_id, workspace_id, delivery_id, source_driver_incident_id)
        REFERENCES logistics.driver_delivery_incident (tenant_id, workspace_id, delivery_id, id),
    CONSTRAINT ck_operational_exception_source_kind CHECK (
        (source_kind = 'DISPATCH_INCIDENT' AND dispatch_order_id IS NOT NULL
            AND source_driver_incident_id IS NULL)
        OR (source_kind = 'DRIVER_INCIDENT' AND dispatch_order_id IS NULL
            AND source_driver_incident_id = source_incident_id)
    ),
    CONSTRAINT ck_operational_exception_type CHECK (type IN ('TEMPERATURE_EXCURSION', 'DELAY',
        'INCOMPLETE_INSTRUCTION', 'ACCESS_BLOCKED', 'CUSTOMER_UNAVAILABLE',
        'DELIVERY_NOT_EXECUTABLE', 'SAFETY_COMPROMISING_DAMAGE')),
    CONSTRAINT ck_operational_exception_severity CHECK (
        (type IN ('DELAY', 'INCOMPLETE_INSTRUCTION') AND severity = 'WARNING')
        OR (type IN ('ACCESS_BLOCKED', 'CUSTOMER_UNAVAILABLE', 'DELIVERY_NOT_EXECUTABLE')
            AND severity = 'BLOCKING')
        OR (type IN ('TEMPERATURE_EXCURSION', 'SAFETY_COMPROMISING_DAMAGE') AND severity = 'CRITICAL')),
    CONSTRAINT ck_operational_exception_reason CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_operational_exception_place CHECK (place IS NULL OR length(btrim(place)) BETWEEN 1 AND 500),
    CONSTRAINT ck_operational_exception_description CHECK (length(btrim(description)) BETWEEN 1 AND 2000)
);

CREATE INDEX ix_operational_exception_delivery
    ON logistics.operational_exception_case (tenant_id, workspace_id, delivery_id, occurred_at, id);

CREATE TRIGGER logistics_operational_exception_case_append_only
    BEFORE UPDATE OR DELETE ON logistics.operational_exception_case FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE logistics.operational_exception_transition (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    exception_id UUID NOT NULL,
    transition_number INTEGER NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    actor_membership_id UUID NOT NULL,
    responsible_membership_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    delivery_version BIGINT NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_operational_exception_transition_sequence
        UNIQUE (tenant_id, workspace_id, exception_id, transition_number),
    CONSTRAINT uq_operational_exception_transition_delivery
        UNIQUE (tenant_id, workspace_id, delivery_id, id),
    CONSTRAINT fk_operational_exception_transition_case
        FOREIGN KEY (tenant_id, workspace_id, delivery_id, exception_id)
        REFERENCES logistics.operational_exception_case (tenant_id, workspace_id, delivery_id, id),
    CONSTRAINT ck_operational_exception_transition_number CHECK (transition_number >= 1),
    CONSTRAINT ck_operational_exception_transition_status CHECK (
        (transition_number = 1 AND from_status IS NULL AND to_status = 'OPEN'
            AND reason_code = 'SOURCE_REPORTED' AND command_type = 'SOURCE'
            AND responsible_membership_id IS NULL)
        OR (transition_number > 1 AND from_status = 'OPEN' AND to_status = 'CLAIMED'
            AND reason_code = 'DRIVER_CLAIMED' AND command_type = 'CLAIM'
            AND responsible_membership_id = actor_membership_id)
        OR (transition_number > 1 AND from_status = 'CLAIMED' AND to_status = 'UNDER_REVIEW'
            AND reason_code = 'DRIVER_REVIEW_STARTED' AND command_type = 'REVIEW'
            AND responsible_membership_id IS NOT NULL)
    ),
    CONSTRAINT ck_operational_exception_transition_delivery_version CHECK (delivery_version >= 0),
    CONSTRAINT ck_operational_exception_transition_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_operational_exception_transition_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_operational_exception_transition_current
    ON logistics.operational_exception_transition
       (tenant_id, workspace_id, delivery_id, exception_id, transition_number DESC);

CREATE TRIGGER logistics_operational_exception_transition_append_only
    BEFORE UPDATE OR DELETE ON logistics.operational_exception_transition FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.operational_exception_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.operational_exception_case FORCE ROW LEVEL SECURITY;
CREATE POLICY v131_operational_exception_case_scope ON logistics.operational_exception_case
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE logistics.operational_exception_transition ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.operational_exception_transition FORCE ROW LEVEL SECURITY;
CREATE POLICY v131_operational_exception_transition_scope ON logistics.operational_exception_transition
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON logistics.operational_exception_case,
            logistics.operational_exception_transition TO nexa_runtime;
    END IF;
END;
$$;
