package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.port.WarehouseInventoryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RestoreLot {
    private final WarehouseInventoryPersistencePort persistence;

    public RestoreLot(WarehouseInventoryPersistencePort persistence) { this.persistence = persistence; }

    @Transactional
    public WarehouseOperationsService.LotSummary execute(CurrentAccessContext context, String lotId, long expectedVersion,
                                                          String reason, String idempotencyKey, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.restoreLot(context, lotId, expectedVersion, reason, idempotencyKey, correlationId);
    }
}
