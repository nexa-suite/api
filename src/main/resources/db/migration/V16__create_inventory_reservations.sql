CREATE TABLE warehouse.inventory_reservation (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, workspace_id UUID NOT NULL, sales_order_id UUID NOT NULL, order_number VARCHAR(14) NOT NULL,
    client_account_id UUID NOT NULL, status VARCHAR(16) NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ, expires_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_reservation_order FOREIGN KEY (tenant_id, workspace_id, sales_order_id) REFERENCES sales.sales_order (tenant_id, workspace_id, id),
    CONSTRAINT fk_reservation_client FOREIGN KEY (tenant_id, workspace_id, client_account_id) REFERENCES sales.client_account (tenant_id, workspace_id, id),
    CONSTRAINT ck_reservation_status CHECK (status IN ('PENDING','RESERVED','SHORTAGE','RELEASED','EXPIRED','CANCELLED','CONSUMED'))
);

CREATE TABLE warehouse.inventory_reservation_line (
    id UUID PRIMARY KEY, reservation_id UUID NOT NULL, catalog_item_id VARCHAR(64) NOT NULL, requested_quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL, shortage_quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    CONSTRAINT fk_reservation_line FOREIGN KEY (reservation_id) REFERENCES warehouse.inventory_reservation(id),
    CONSTRAINT uq_reservation_line_item UNIQUE (reservation_id, catalog_item_id),
    CONSTRAINT ck_reservation_line_quantity CHECK (requested_quantity > 0 AND shortage_quantity >= 0 AND shortage_quantity <= requested_quantity)
);

CREATE TABLE warehouse.inventory_reservation_allocation (
    id UUID PRIMARY KEY, reservation_line_id UUID NOT NULL, lot_id UUID NOT NULL, quantity NUMERIC(19,4) NOT NULL,
    unit VARCHAR(32) NOT NULL, expiration_date DATE NOT NULL,
    CONSTRAINT fk_allocation_line FOREIGN KEY (reservation_line_id) REFERENCES warehouse.inventory_reservation_line(id),
    CONSTRAINT fk_allocation_lot FOREIGN KEY (lot_id) REFERENCES warehouse.inventory_lot(id),
    CONSTRAINT uq_allocation_line_lot UNIQUE (reservation_line_id, lot_id), CONSTRAINT ck_allocation_quantity CHECK (quantity > 0)
);

CREATE TABLE warehouse.reservation_shortage (
    id UUID PRIMARY KEY, reservation_line_id UUID NOT NULL, quantity NUMERIC(19,4) NOT NULL, reason VARCHAR(2000) NOT NULL,
    CONSTRAINT fk_shortage_line FOREIGN KEY (reservation_line_id) REFERENCES warehouse.inventory_reservation_line(id), CONSTRAINT ck_shortage_quantity CHECK (quantity > 0)
);

CREATE TABLE warehouse.command_idempotency (
    tenant_id UUID NOT NULL, workspace_id UUID NOT NULL, operation VARCHAR(80) NOT NULL, idempotency_key VARCHAR(160) NOT NULL,
    response_json TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (tenant_id, workspace_id, operation, idempotency_key)
);

CREATE UNIQUE INDEX uq_active_reservation_order ON warehouse.inventory_reservation (tenant_id, workspace_id, sales_order_id)
WHERE status IN ('PENDING','RESERVED','SHORTAGE');
CREATE INDEX ix_reservation_scope_status ON warehouse.inventory_reservation (tenant_id, workspace_id, status, created_at DESC, id);
CREATE INDEX ix_reservation_expiry ON warehouse.inventory_reservation (status, expires_at, id);
CREATE INDEX ix_reservation_order ON warehouse.inventory_reservation (tenant_id, workspace_id, sales_order_id, status);
CREATE INDEX ix_allocation_reservation ON warehouse.inventory_reservation_allocation (reservation_line_id, lot_id);

CREATE TRIGGER warehouse_reservation_append_only BEFORE UPDATE OR DELETE ON warehouse.inventory_reservation_allocation FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TRIGGER warehouse_shortage_append_only BEFORE UPDATE OR DELETE ON warehouse.reservation_shortage FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
