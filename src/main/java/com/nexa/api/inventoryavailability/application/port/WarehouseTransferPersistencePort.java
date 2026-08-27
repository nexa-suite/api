package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;

public interface WarehouseTransferPersistencePort {
    WarehouseOperationsService.Page<WarehouseOperationsService.TransferSummary> transfers(
            CurrentAccessContext context, String sourceWarehouseId, String destinationWarehouseId,
            int page, int size);

    WarehouseOperationsService.TransferSummary transfer(CurrentAccessContext context, String id);

    WarehouseOperationsService.TransferSummary transfer(
            CurrentAccessContext context, WarehouseOperationsService.TransferCommand command,
            long expectedSourceVersion, String idempotencyKey, String correlationId);
}
