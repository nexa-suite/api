package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;

final class WarehouseApplicationAuthorization {
    private WarehouseApplicationAuthorization() { }

    static void read(CurrentAccessContext context) { context.requirePermission(Permission.WAREHOUSE_READ); }
    static void write(CurrentAccessContext context) { context.requirePermission(Permission.WAREHOUSE_WRITE); }
    static void fulfillmentRead(CurrentAccessContext context) { context.requirePermission(Permission.FULFILLMENT_READ); }
}
