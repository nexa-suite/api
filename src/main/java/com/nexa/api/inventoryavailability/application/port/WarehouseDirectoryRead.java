package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService.Page;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService.WarehouseSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Warehouse directory queries constrained by the caller's current object access. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface WarehouseDirectoryRead {
    Page<WarehouseSummary> list(CurrentAccessContext context, int page, int size, String sort);

    WarehouseSummary get(CurrentAccessContext context, String id);
}
