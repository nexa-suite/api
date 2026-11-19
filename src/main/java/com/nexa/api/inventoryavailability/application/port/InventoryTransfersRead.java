package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Read-only access to the scoped inventory transfer list. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface InventoryTransfersRead {
    WarehouseOperationsService.Page<WarehouseOperationsService.TransferSummary> list(
            CurrentAccessContext context, String sourceWarehouseId, String destinationWarehouseId, int page, int size);
}
