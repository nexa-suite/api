package com.nexa.api.warehouse.application.port;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;

public interface WarehouseSafetyStockPersistencePort {
    WarehouseOperationsService.Page<WarehouseOperationsService.SafetyStockSummary> safetyStocks(
            CurrentAccessContext context, String warehouseId, String skuId, int page, int size);

    WarehouseOperationsService.SafetyStockSummary safetyStock(CurrentAccessContext context, String id);

    WarehouseOperationsService.SafetyStockSummary upsertSafetyStock(
            CurrentAccessContext context, WarehouseOperationsService.SafetyStockCommand command,
            long expectedVersion, String idempotencyKey, String correlationId);
}
