package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.function.Function;

/** Runs one Warehouse HTTP request against its authoritative persistence binding. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface WarehouseOperationsRequestRunner {
    <T> T execute(CurrentAccessContext context, Function<WarehouseOperationsService, T> operation);

    interface Receiving {
        WarehouseOperationsService.Page<WarehouseOperationsService.ZoneSummary> zones(
                CurrentAccessContext context, String warehouseId, int page, int size);

        WarehouseOperationsService.LotSummary receive(CurrentAccessContext context,
                WarehouseOperationsService.Receipt receipt, String key, String correlation);
    }

}
