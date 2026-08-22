package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseSafetyStockPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ManageSafetyStock {
    private final WarehouseSafetyStockPersistencePort persistence;

    public ManageSafetyStock(WarehouseSafetyStockPersistencePort persistence) {
        this.persistence = persistence;
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.SafetyStockSummary> list(
            CurrentAccessContext context, String warehouseId, String skuId, int page, int size) {
        WarehouseApplicationAuthorization.read(context);
        return persistence.safetyStocks(context, warehouseId, skuId, page, size);
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.SafetyStockSummary get(CurrentAccessContext context, String id) {
        WarehouseApplicationAuthorization.read(context);
        return persistence.safetyStock(context, id);
    }

    @Transactional
    public WarehouseOperationsService.SafetyStockSummary upsert(
            CurrentAccessContext context, WarehouseOperationsService.SafetyStockCommand command,
            long expectedVersion, String idempotencyKey, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.upsertSafetyStock(context, command, expectedVersion, idempotencyKey, correlationId);
    }
}
