package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseOutboxPort;
import com.nexa.api.warehouse.application.port.WarehouseReservationPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ReserveInventory {
    private final WarehouseReservationPersistencePort persistence;
    private final WarehouseOutboxPort outbox;

    public ReserveInventory(WarehouseReservationPersistencePort persistence, WarehouseOutboxPort outbox) {
        this.persistence = persistence;
        this.outbox = outbox;
    }

    @Transactional
    public WarehouseOperationsService.ReservationDetail execute(CurrentAccessContext context, String orderId,
                                                                 long expectedVersion, String idempotencyKey,
                                                                 String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        WarehouseOperationsService.ReservationDetail result = persistence.reserve(context, orderId, expectedVersion,
                idempotencyKey, correlationId);
        if ("RESERVED".equals(result.status())) outbox.fulfillmentReady(context, result, correlationId);
        return result;
    }
}
