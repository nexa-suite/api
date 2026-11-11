package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import java.util.List;

@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface InventoryLotsRead {
    WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> list(CurrentAccessContext context,
            String catalogItemId, String warehouseId, String zoneId, String status, int page, int size, String sort);
    WarehouseOperationsService.LotSummary lot(CurrentAccessContext context, String id);
    List<WarehouseOperationsService.Availability> warehouseAvailability(CurrentAccessContext context, String warehouseId, List<String> ids);
}
