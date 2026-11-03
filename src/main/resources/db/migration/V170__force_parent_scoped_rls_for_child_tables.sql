-- Extend forced RLS to tenant-owned child rows that inherit their scope from
-- an already protected parent. This closes direct SQL access paths without
-- duplicating Tenant/Workspace columns or rewriting published migrations.

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

-- These settings are Workspace-owned. The Workspace parent both confirms the
-- Tenant/Workspace pair and applies its own RLS scope.
ALTER TABLE tenant_management.workspace_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.workspace_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY workspace_settings_parent_scope
    ON tenant_management.workspace_settings
    USING (EXISTS (
        SELECT 1
        FROM tenant_management.workspace workspace
        WHERE workspace.id = workspace_id
          AND workspace.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND workspace.id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM tenant_management.workspace workspace
        WHERE workspace.id = workspace_id
          AND workspace.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND workspace.id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE tenant_management.operational_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.operational_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY operational_settings_parent_scope
    ON tenant_management.operational_settings
    USING (EXISTS (
        SELECT 1
        FROM tenant_management.workspace workspace
        WHERE workspace.id = workspace_id
          AND workspace.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND workspace.id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM tenant_management.workspace workspace
        WHERE workspace.id = workspace_id
          AND workspace.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND workspace.id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE tenant_management.notification_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.notification_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_preference_parent_scope
    ON tenant_management.notification_preference
    USING (EXISTS (
        SELECT 1
        FROM tenant_management.workspace workspace
        WHERE workspace.id = workspace_id
          AND workspace.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND workspace.id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM tenant_management.workspace workspace
        WHERE workspace.id = workspace_id
          AND workspace.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND workspace.id::text = current_setting('app.current_workspace_id', true)
    ));

ALTER TABLE tenant_management.organization_invitation_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.organization_invitation_role FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_invitation_role_parent_scope
    ON tenant_management.organization_invitation_role
    USING (EXISTS (
        SELECT 1
        FROM tenant_management.organization_invitation invitation
        WHERE invitation.id = invitation_id
          AND invitation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND invitation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM tenant_management.organization_invitation invitation
        WHERE invitation.id = invitation_id
          AND invitation.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND invitation.workspace_id::text = current_setting('app.current_workspace_id', true)
    ));

-- System role templates remain visible to all Tenant scopes for authorization
-- resolution. Writes remain limited to custom roles owned by the current scope.
ALTER TABLE tenant_management.role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_management.role_permission FORCE ROW LEVEL SECURITY;
CREATE POLICY role_permission_visible_role_read
    ON tenant_management.role_permission
    FOR SELECT
    USING (EXISTS (
        SELECT 1
        FROM tenant_management.role_definition role_definition
        WHERE role_definition.id = role_id
    ));
CREATE POLICY role_permission_custom_role_insert
    ON tenant_management.role_permission
    FOR INSERT
    WITH CHECK (EXISTS (
        SELECT 1
        FROM tenant_management.role_definition role_definition
        WHERE role_definition.id = role_id
          AND role_definition.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND (role_definition.workspace_id IS NULL
               OR role_definition.workspace_id::text = current_setting('app.current_workspace_id', true))
          AND role_definition.role_type = 'CUSTOM'
    ));
CREATE POLICY role_permission_custom_role_update
    ON tenant_management.role_permission
    FOR UPDATE
    USING (EXISTS (
        SELECT 1
        FROM tenant_management.role_definition role_definition
        WHERE role_definition.id = role_id
          AND role_definition.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND (role_definition.workspace_id IS NULL
               OR role_definition.workspace_id::text = current_setting('app.current_workspace_id', true))
          AND role_definition.role_type = 'CUSTOM'
    ))
    WITH CHECK (EXISTS (
        SELECT 1
        FROM tenant_management.role_definition role_definition
        WHERE role_definition.id = role_id
          AND role_definition.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND (role_definition.workspace_id IS NULL
               OR role_definition.workspace_id::text = current_setting('app.current_workspace_id', true))
          AND role_definition.role_type = 'CUSTOM'
    ));
CREATE POLICY role_permission_custom_role_delete
    ON tenant_management.role_permission
    FOR DELETE
    USING (EXISTS (
        SELECT 1
        FROM tenant_management.role_definition role_definition
        WHERE role_definition.id = role_id
          AND role_definition.tenant_id::text = current_setting('app.current_tenant_id', true)
          AND (role_definition.workspace_id IS NULL
               OR role_definition.workspace_id::text = current_setting('app.current_workspace_id', true))
          AND role_definition.role_type = 'CUSTOM'
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
