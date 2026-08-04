package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseInventoryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class BlockLot {
    private final WarehouseInventoryPersistencePort persistence;

    public BlockLot(WarehouseInventoryPersistencePort persistence) { this.persistence = persistence; }

    @Transactional
    public WarehouseOperationsService.LotSummary execute(CurrentAccessContext context, String lotId, long expectedVersion,
                                                          String reason, String idempotencyKey, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.blockLot(context, lotId, expectedVersion, reason, idempotencyKey, correlationId);
    }
}
