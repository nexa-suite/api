-- Keep credit reservations distinct from physical inventory allocation and
-- carry the reservation from PR submission to its resulting Sales Order.
ALTER TABLE payments.credit_reservation
    ADD COLUMN purchase_request_id UUID,
    ADD COLUMN sales_order_id UUID;

ALTER TABLE payments.credit_reservation
    ADD CONSTRAINT fk_credit_reservation_purchase_request
        FOREIGN KEY (tenant_id, workspace_id, purchase_request_id)
        REFERENCES sales.purchase_request (tenant_id, workspace_id, id),
    ADD CONSTRAINT fk_credit_reservation_sales_order
        FOREIGN KEY (tenant_id, workspace_id, sales_order_id)
        REFERENCES sales.sales_order (tenant_id, workspace_id, id);

CREATE UNIQUE INDEX uq_credit_reservation_active_purchase_request
    ON payments.credit_reservation (tenant_id, workspace_id, purchase_request_id)
    WHERE purchase_request_id IS NOT NULL AND status='RESERVED';
CREATE UNIQUE INDEX uq_credit_reservation_active_sales_order
    ON payments.credit_reservation (tenant_id, workspace_id, sales_order_id)
    WHERE sales_order_id IS NOT NULL AND status='RESERVED';
CREATE INDEX ix_credit_reservation_purchase_request
    ON payments.credit_reservation (tenant_id, workspace_id, purchase_request_id, status);
