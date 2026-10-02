CREATE TABLE logistics.operational_exception_coordination_transition (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    exception_id UUID NOT NULL,
    transition_number INTEGER NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    actor_membership_id UUID NOT NULL,
    responsible_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500) NOT NULL,
    note VARCHAR(2000),
    resolution VARCHAR(2000),
    outcome VARCHAR(40),
    delivery_version BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_operational_exception_coordination_sequence
        UNIQUE (tenant_id,workspace_id,exception_id,transition_number),
    CONSTRAINT uq_operational_exception_coordination_scope_id
        UNIQUE (tenant_id,workspace_id,id),
    CONSTRAINT uq_operational_exception_coordination_key
        UNIQUE (tenant_id,workspace_id,actor_membership_id,command_type,idempotency_key),
    CONSTRAINT fk_operational_exception_coordination_case
        FOREIGN KEY (tenant_id,workspace_id,delivery_id,exception_id)
        REFERENCES logistics.operational_exception_case (tenant_id,workspace_id,delivery_id,id),
    CONSTRAINT ck_operational_exception_coordination_number CHECK (transition_number >= 1),
    CONSTRAINT ck_operational_exception_coordination_state CHECK (
        (command_type='CLAIM' AND transition_number=1 AND from_status='OPEN' AND to_status='CLAIMED'
            AND responsible_membership_id=actor_membership_id)
        OR (command_type='ASSIGN' AND from_status IN ('OPEN','CLAIMED','FOLLOW_UP') AND to_status='CLAIMED')
        OR (command_type='FOLLOW_UP' AND from_status IN ('CLAIMED','FOLLOW_UP') AND to_status='FOLLOW_UP'
            AND responsible_membership_id=actor_membership_id)
        OR (command_type='RESOLVE' AND from_status IN ('CLAIMED','FOLLOW_UP') AND to_status='RESOLVED'
            AND responsible_membership_id=actor_membership_id AND resolution IS NOT NULL
            AND outcome='WARNING_CONDITION_ADDRESSED')
        OR (command_type='CLOSE' AND from_status='RESOLVED' AND to_status='CLOSED'
            AND responsible_membership_id=actor_membership_id AND resolution IS NOT NULL
            AND outcome='WARNING_CONDITION_ADDRESSED')
    ),
    CONSTRAINT ck_operational_exception_coordination_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_operational_exception_coordination_note CHECK (note IS NULL OR length(btrim(note)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_operational_exception_coordination_resolution CHECK (
        (to_status IN ('RESOLVED','CLOSED') AND length(btrim(resolution)) BETWEEN 1 AND 2000
            AND outcome='WARNING_CONDITION_ADDRESSED')
        OR (to_status NOT IN ('RESOLVED','CLOSED') AND resolution IS NULL AND outcome IS NULL)
    ),
    CONSTRAINT ck_operational_exception_coordination_version CHECK (delivery_version >= 0),
    CONSTRAINT ck_operational_exception_coordination_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_operational_exception_coordination_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_operational_exception_coordination_current
    ON logistics.operational_exception_coordination_transition
       (tenant_id,workspace_id,exception_id,transition_number DESC);

CREATE TRIGGER logistics_operational_exception_coordination_append_only
    BEFORE UPDATE OR DELETE ON logistics.operational_exception_coordination_transition FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.operational_exception_coordination_transition ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.operational_exception_coordination_transition FORCE ROW LEVEL SECURITY;
CREATE POLICY v141_operational_exception_coordination_scope
    ON logistics.operational_exception_coordination_transition
    USING (tenant_id::text=current_setting('app.current_tenant_id',true)
       AND workspace_id::text=current_setting('app.current_workspace_id',true))
    WITH CHECK (tenant_id::text=current_setting('app.current_tenant_id',true)
            AND workspace_id::text=current_setting('app.current_workspace_id',true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_runtime') THEN
        GRANT SELECT,INSERT ON logistics.operational_exception_coordination_transition TO nexa_runtime;
    END IF;
END;
$$;
