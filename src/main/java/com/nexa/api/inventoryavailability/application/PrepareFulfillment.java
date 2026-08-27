package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.port.WarehouseReservationPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class PrepareFulfillment {
    private final WarehouseReservationPersistencePort persistence;

    public PrepareFulfillment(WarehouseReservationPersistencePort persistence) { this.persistence = persistence; }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.ReservationPreview execute(CurrentAccessContext context, String orderId) {
        WarehouseApplicationAuthorization.fulfillmentRead(context);
        return persistence.preview(context, orderId);
    }
}
