package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.port.WarehouseInventoryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class CycleCountInventory {
    private final WarehouseInventoryPersistencePort persistence;

    public CycleCountInventory(WarehouseInventoryPersistencePort persistence) {
        this.persistence = persistence;
    }

    @Transactional
    public WarehouseOperationsService.CycleCountRecord record(
            CurrentAccessContext context, String lotId,
            WarehouseOperationsService.CycleCountCommand command, long expectedLotVersion,
            String idempotencyKey, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.recordCycleCount(context, lotId, command, expectedLotVersion,
                idempotencyKey, correlationId);
    }

    @Transactional
    public WarehouseOperationsService.CycleCountCorrection applyCorrection(
            CurrentAccessContext context, String countId, long expectedLotVersion,
            String idempotencyKey, String correlationId) {
        WarehouseApplicationAuthorization.adjust(context);
        return persistence.applyCycleCountCorrection(context, countId, expectedLotVersion,
                idempotencyKey, correlationId);
    }
}
