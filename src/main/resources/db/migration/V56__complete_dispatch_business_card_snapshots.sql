ALTER TABLE logistics.dispatch_order
    ADD COLUMN client_code_snapshot VARCHAR(32),
    ADD COLUMN client_name_snapshot VARCHAR(200),
    ADD COLUMN delivery_area_snapshot TEXT,
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

UPDATE logistics.dispatch_order d
SET client_code_snapshot = c.code,
    client_name_snapshot = COALESCE(NULLIF(c.commercial_name, ''), c.business_name),
    delivery_area_snapshot = d.destination_snapshot,
    priority = o.priority
FROM sales.sales_order o
JOIN sales.client_account c
  ON c.tenant_id = o.tenant_id
 AND c.workspace_id = o.workspace_id
 AND c.id = o.client_account_id
WHERE o.tenant_id = d.tenant_id
  AND o.workspace_id = d.workspace_id
  AND o.id = d.sales_order_id;

ALTER TABLE logistics.dispatch_order
    ADD CONSTRAINT ck_dispatch_priority CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT'));

CREATE INDEX ix_dispatch_business_card
    ON logistics.dispatch_order (tenant_id, workspace_id, priority, delivery_window_start, updated_at DESC, id);
