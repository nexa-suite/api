CREATE SCHEMA logistics;

CREATE TABLE logistics.dispatch_number_counter (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    dispatch_year INTEGER NOT NULL,
    next_value BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, dispatch_year),
    CONSTRAINT fk_dispatch_counter_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_dispatch_counter_value CHECK (next_value > 0)
);

CREATE TABLE logistics.dispatch_order (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    dispatch_number VARCHAR(20) NOT NULL,
    inventory_reservation_id UUID NOT NULL,
    sales_order_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    destination_snapshot TEXT,
    delivery_window_start TIMESTAMPTZ,
    delivery_window_end TIMESTAMPTZ,
    eta TIMESTAMPTZ,
    responsible_membership_id UUID,
    responsible_display_name_snapshot VARCHAR(160),
    vehicle_reference VARCHAR(120),
    route_name VARCHAR(160),
    temperature_min NUMERIC(9,4),
    temperature_max NUMERIC(9,4),
    temperature_unit VARCHAR(16),
    temperature_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dispatch_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_dispatch_scope_number UNIQUE (tenant_id, workspace_id, dispatch_number),
    CONSTRAINT uq_dispatch_reservation UNIQUE (tenant_id, workspace_id, inventory_reservation_id),
    CONSTRAINT fk_dispatch_workspace FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_dispatch_reservation FOREIGN KEY (tenant_id, workspace_id, inventory_reservation_id)
        REFERENCES warehouse.inventory_reservation (tenant_id, workspace_id, id),
    CONSTRAINT fk_dispatch_order FOREIGN KEY (tenant_id, workspace_id, sales_order_id)
        REFERENCES sales.sales_order (tenant_id, workspace_id, id),
    CONSTRAINT fk_dispatch_client FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT ck_dispatch_status CHECK (status IN ('READY_FOR_OPERATIONS','PREPARING','ASSIGNED','SCHEDULED',
        'READY_FOR_ROUTE','IN_ROUTE','DELIVERED','INCIDENT','REPROGRAMMED','CANCELLED')),
    CONSTRAINT ck_dispatch_temperature_status CHECK (temperature_status IN ('WITHIN_RANGE','OUT_OF_RANGE','UNKNOWN')),
    CONSTRAINT ck_dispatch_temperature_range CHECK (temperature_min IS NULL OR temperature_max IS NULL OR temperature_min <= temperature_max),
    CONSTRAINT ck_dispatch_version CHECK (version >= 0)
);

CREATE TABLE logistics.dispatch_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    dispatch_order_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    actor_membership_id UUID NOT NULL,
    buyer_visible BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_dispatch_event_dispatch FOREIGN KEY (tenant_id, workspace_id, dispatch_order_id)
        REFERENCES logistics.dispatch_order (tenant_id, workspace_id, id)
);

CREATE TABLE logistics.command_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, operation, idempotency_key)
);

CREATE INDEX ix_dispatch_scope_status ON logistics.dispatch_order (tenant_id, workspace_id, status, updated_at DESC, id);
CREATE INDEX ix_dispatch_scope_client ON logistics.dispatch_order (tenant_id, workspace_id, client_account_id, updated_at DESC, id);
CREATE INDEX ix_dispatch_window ON logistics.dispatch_order (tenant_id, workspace_id, delivery_window_start, status, id);
CREATE INDEX ix_dispatch_event_dispatch ON logistics.dispatch_event (tenant_id, workspace_id, dispatch_order_id, occurred_at, id);

CREATE TRIGGER logistics_dispatch_event_append_only
    BEFORE UPDATE OR DELETE ON logistics.dispatch_event FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER logistics_command_idempotency_append_only
    BEFORE UPDATE OR DELETE ON logistics.command_idempotency FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();
