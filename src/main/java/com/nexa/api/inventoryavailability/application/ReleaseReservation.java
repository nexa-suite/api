package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.port.WarehouseReservationPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ReleaseReservation {
    private final WarehouseReservationPersistencePort persistence;

    public ReleaseReservation(WarehouseReservationPersistencePort persistence) { this.persistence = persistence; }

    @Transactional
    public WarehouseOperationsService.ReservationDetail execute(CurrentAccessContext context, String reservationId,
                                                                 long expectedVersion, String idempotencyKey,
                                                                 String reason, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.release(context, reservationId, expectedVersion, idempotencyKey, reason, correlationId, false);
    }

    @Transactional
    public WarehouseOperationsService.ReservationDetail expire(CurrentAccessContext context, String reservationId,
                                                                long expectedVersion, String idempotencyKey,
                                                                String reason, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.release(context, reservationId, expectedVersion, idempotencyKey, reason, correlationId, true);
    }
}
