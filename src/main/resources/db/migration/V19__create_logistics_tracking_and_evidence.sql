CREATE TABLE logistics.proof_of_delivery (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    dispatch_order_id UUID NOT NULL,
    receiver_name VARCHAR(160) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    notes VARCHAR(2000),
    photo_evidence_declared BOOLEAN NOT NULL DEFAULT FALSE,
    signature_evidence_declared BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_pod_dispatch UNIQUE (tenant_id, workspace_id, dispatch_order_id),
    CONSTRAINT fk_pod_dispatch FOREIGN KEY (tenant_id, workspace_id, dispatch_order_id)
        REFERENCES logistics.dispatch_order (tenant_id, workspace_id, id),
    CONSTRAINT ck_pod_status CHECK (status IN ('PENDING','COMPLETED'))
);

CREATE TABLE logistics.temperature_reading (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    dispatch_order_id UUID NOT NULL,
    value NUMERIC(9,4),
    unit VARCHAR(16) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_temperature_dispatch FOREIGN KEY (tenant_id, workspace_id, dispatch_order_id)
        REFERENCES logistics.dispatch_order (tenant_id, workspace_id, id),
    CONSTRAINT ck_temperature_status CHECK (status IN ('WITHIN_RANGE','OUT_OF_RANGE','UNKNOWN')),
    CONSTRAINT ck_temperature_value CHECK (value IS NULL OR (value > -1000 AND value < 1000))
);

CREATE TABLE logistics.delivery_incident (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    dispatch_order_id UUID NOT NULL,
    incident_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    buyer_visible BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(2000) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    resolution VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_incident_dispatch FOREIGN KEY (tenant_id, workspace_id, dispatch_order_id)
        REFERENCES logistics.dispatch_order (tenant_id, workspace_id, id),
    CONSTRAINT ck_incident_type CHECK (incident_type IN ('TEMPERATURE_EXCURSION','DELAY','VEHICLE_ISSUE',
        'DELIVERY_REFUSED','ADDRESS_ISSUE','OTHER')),
    CONSTRAINT ck_incident_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);

CREATE INDEX ix_temperature_dispatch_time ON logistics.temperature_reading (tenant_id, workspace_id, dispatch_order_id, recorded_at DESC, id);
CREATE INDEX ix_temperature_alerts ON logistics.temperature_reading (tenant_id, workspace_id, status, recorded_at DESC, id);
CREATE INDEX ix_incident_dispatch_time ON logistics.delivery_incident (tenant_id, workspace_id, dispatch_order_id, occurred_at DESC, id);
CREATE INDEX ix_incident_active ON logistics.delivery_incident (tenant_id, workspace_id, severity, occurred_at DESC, id);

CREATE OR REPLACE VIEW logistics.buyer_delivery_tracking AS
SELECT d.tenant_id, d.workspace_id, d.id AS dispatch_order_id, d.dispatch_number,
       d.sales_order_id, d.client_account_id, d.status, d.destination_snapshot,
       d.delivery_window_start, d.delivery_window_end, d.eta, d.updated_at,
       p.status AS pod_status
FROM logistics.dispatch_order d
LEFT JOIN logistics.proof_of_delivery p
  ON p.tenant_id = d.tenant_id AND p.workspace_id = d.workspace_id AND p.dispatch_order_id = d.id;

CREATE TRIGGER logistics_pod_append_only
    BEFORE UPDATE OR DELETE ON logistics.proof_of_delivery FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_temperature_append_only
    BEFORE UPDATE OR DELETE ON logistics.temperature_reading FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_incident_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_incident FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
