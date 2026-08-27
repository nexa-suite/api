package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;

/** Application-facing publication port for committed Warehouse facts. */
public interface WarehouseOutboxPort {
    void fulfillmentReady(CurrentAccessContext context, WarehouseOperationsService.ReservationDetail reservation,
                          String correlationId);
}
