package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;

final class WarehouseApplicationAuthorization {
    private WarehouseApplicationAuthorization() { }

    static void read(CurrentAccessContext context) { context.requirePermission(Permission.WAREHOUSE_READ); }
    static void write(CurrentAccessContext context) { context.requirePermission(Permission.WAREHOUSE_WRITE); }
    static void adjust(CurrentAccessContext context) { context.requirePermission(PermissionKey.INVENTORY_ADJUST); }
    static void waste(CurrentAccessContext context) { context.requirePermission(PermissionKey.INVENTORY_WASTE); }
    static void release(CurrentAccessContext context) { context.requirePermission(PermissionKey.INVENTORY_RELEASE); }
    static void fulfillmentRead(CurrentAccessContext context) { context.requirePermission(Permission.FULFILLMENT_READ); }
}
