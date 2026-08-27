package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.port.WarehouseInventoryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Profile("!test")
public class RegisterWaste {
    private final WarehouseInventoryPersistencePort persistence;

    public RegisterWaste(WarehouseInventoryPersistencePort persistence) { this.persistence = persistence; }

    @Transactional
    public WarehouseOperationsService.LotSummary execute(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                          String reason, long expectedVersion, String idempotencyKey,
                                                          String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.waste(context, lotId, quantity, reason, expectedVersion, idempotencyKey, correlationId);
    }
}
