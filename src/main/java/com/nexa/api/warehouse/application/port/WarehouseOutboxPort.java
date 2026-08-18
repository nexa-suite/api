package com.nexa.api.warehouse.application.port;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;

/** Application-facing publication port for committed Warehouse facts. */
public interface WarehouseOutboxPort {
    void fulfillmentReady(CurrentAccessContext context, WarehouseOperationsService.ReservationDetail reservation,
                          String correlationId);
}
