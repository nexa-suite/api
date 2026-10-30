-- Keep child business relations protected in the physical Tenant database.
-- Parent rows carry the authoritative Tenant/Workspace scope; the local
-- notification preference projection resolves that scope through the verified
-- Tenant/Workspace anchor because central Workspace rows are not copied here.

ALTER TABLE sales.purchase_request_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.purchase_request_line FORCE ROW LEVEL SECURITY;
CREATE POLICY purchase_request_line_parent_scope
    ON sales.purchase_request_line
    USING (EXISTS (
        SELECT 1
        FROM sales.purchase_request request
        WHERE request.id = purchase_request_id
          AND request.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND request.workspace_id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM sales.purchase_request request
        WHERE request.id = purchase_request_id
          AND request.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND request.workspace_id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE sales.sales_order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.sales_order_line FORCE ROW LEVEL SECURITY;
CREATE POLICY sales_order_line_parent_scope
    ON sales.sales_order_line
    USING (EXISTS (
        SELECT 1
        FROM sales.sales_order sales_order
        WHERE sales_order.id = sales_order_id
          AND sales_order.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND sales_order.workspace_id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM sales.sales_order sales_order
        WHERE sales_order.id = sales_order_id
          AND sales_order.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND sales_order.workspace_id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE warehouse.inventory_reservation_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_reservation_line FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_reservation_line_parent_scope
    ON warehouse.inventory_reservation_line
    USING (EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation reservation
        WHERE reservation.id = reservation_id
          AND reservation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND reservation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation reservation
        WHERE reservation.id = reservation_id
          AND reservation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND reservation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE warehouse.inventory_reservation_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.inventory_reservation_allocation FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_reservation_allocation_parent_scope
    ON warehouse.inventory_reservation_allocation
    USING (EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation_line line
        JOIN warehouse.inventory_reservation reservation ON reservation.id = line.reservation_id
        WHERE line.id = reservation_line_id
          AND reservation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND reservation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation_line line
        JOIN warehouse.inventory_reservation reservation ON reservation.id = line.reservation_id
        WHERE line.id = reservation_line_id
          AND reservation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND reservation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE warehouse.reservation_shortage ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse.reservation_shortage FORCE ROW LEVEL SECURITY;
CREATE POLICY reservation_shortage_parent_scope
    ON warehouse.reservation_shortage
    USING (EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation_line line
        JOIN warehouse.inventory_reservation reservation ON reservation.id = line.reservation_id
        WHERE line.id = reservation_line_id
          AND reservation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND reservation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM warehouse.inventory_reservation_line line
        JOIN warehouse.inventory_reservation reservation ON reservation.id = line.reservation_id
        WHERE line.id = reservation_line_id
          AND reservation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND reservation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE tenant_management.notification_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.notification_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_preference_parent_scope
    ON tenant_management.notification_preference
    USING (EXISTS (
        SELECT 1
        FROM nexa_platform.tenant_workspace_scope_anchor anchor
        WHERE anchor.workspace_id = notification_preference.workspace_id
          AND anchor.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND anchor.workspace_id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM nexa_platform.tenant_workspace_scope_anchor anchor
        WHERE anchor.workspace_id = notification_preference.workspace_id
          AND anchor.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND anchor.workspace_id::text = current_setting('app.current_workspace_id', true)
    ));
