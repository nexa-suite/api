-- Append-only driver incident facts are bound to the assigned delivery attempt.
CREATE TABLE logistics.driver_delivery_incident (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    delivery_attempt_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    place VARCHAR(500) NOT NULL,
    reported_by_membership_id UUID NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL,
    delivery_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_driver_delivery_incident_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT fk_driver_delivery_incident_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_driver_delivery_incident_delivery FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT ck_driver_delivery_incident_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_driver_delivery_incident_description CHECK (length(btrim(description)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_driver_delivery_incident_place CHECK (length(btrim(place)) BETWEEN 1 AND 500),
    CONSTRAINT ck_driver_delivery_incident_version CHECK (delivery_version >= 0),
    CONSTRAINT ck_driver_delivery_incident_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_driver_delivery_incident_attempt_time
    ON logistics.driver_delivery_incident (tenant_id, workspace_id, delivery_id, delivery_attempt_id, reported_at, id);

CREATE OR REPLACE FUNCTION logistics.validate_driver_delivery_incident_attempt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM logistics.delivery_active_attempt active
        WHERE active.tenant_id=NEW.tenant_id AND active.workspace_id=NEW.workspace_id
          AND active.delivery_id=NEW.delivery_id AND active.id=NEW.delivery_attempt_id
    ) AND NOT EXISTS (
        SELECT 1 FROM logistics.delivery_attempt terminal
        WHERE terminal.tenant_id=NEW.tenant_id AND terminal.workspace_id=NEW.workspace_id
          AND terminal.delivery_id=NEW.delivery_id AND terminal.id=NEW.delivery_attempt_id
    ) THEN
        RAISE EXCEPTION 'Driver incident attempt is outside its delivery scope';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER logistics_driver_delivery_incident_attempt_scope
    BEFORE INSERT ON logistics.driver_delivery_incident FOR EACH ROW
    EXECUTE FUNCTION logistics.validate_driver_delivery_incident_attempt();

CREATE TRIGGER logistics_driver_delivery_incident_append_only
    BEFORE UPDATE OR DELETE ON logistics.driver_delivery_incident FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE logistics.driver_delivery_incident_evidence (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    evidence_object_id UUID NOT NULL,
    attached_by_membership_id UUID NOT NULL,
    attached_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_driver_delivery_incident_evidence PRIMARY KEY
        (tenant_id, workspace_id, incident_id, evidence_object_id),
    CONSTRAINT fk_driver_delivery_incident_evidence_incident FOREIGN KEY (tenant_id, workspace_id, incident_id)
        REFERENCES logistics.driver_delivery_incident (tenant_id, workspace_id, id)
);

CREATE INDEX ix_driver_delivery_incident_evidence_subject
    ON logistics.driver_delivery_incident_evidence (tenant_id, workspace_id, evidence_object_id);

CREATE TRIGGER logistics_driver_delivery_incident_evidence_append_only
    BEFORE UPDATE OR DELETE ON logistics.driver_delivery_incident_evidence FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

DO $$
DECLARE
    target regclass;
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'logistics.driver_delivery_incident',
        'logistics.driver_delivery_incident_evidence'
    ] LOOP
        target := table_name::regclass;
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
        EXECUTE format('CREATE POLICY v119_driver_incident_scope ON %s '
            'USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) '
            'AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) '
            'WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) '
            'AND workspace_id::text = current_setting(''app.current_workspace_id'', true))', target);
    END LOOP;
END;
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT USAGE ON SCHEMA logistics TO nexa_runtime;
        GRANT SELECT, INSERT ON logistics.driver_delivery_incident,
            logistics.driver_delivery_incident_evidence TO nexa_runtime;
    END IF;
END;
$$;
