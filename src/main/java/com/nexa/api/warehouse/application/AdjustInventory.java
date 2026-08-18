package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseInventoryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Profile("!test")
public class AdjustInventory {
    private final WarehouseInventoryPersistencePort persistence;

    public AdjustInventory(WarehouseInventoryPersistencePort persistence) { this.persistence = persistence; }

    @Transactional
    public WarehouseOperationsService.LotSummary execute(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                          boolean inbound, String reason, long expectedVersion,
                                                          String idempotencyKey, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.adjust(context, lotId, quantity, inbound, reason, expectedVersion, idempotencyKey, correlationId);
    }
}
