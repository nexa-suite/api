package com.nexa.api.warehouse.application.port;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;

public interface WarehouseReservationPersistencePort {
    WarehouseOperationsService.ReservationPreview preview(CurrentAccessContext context, String orderId);
    WarehouseOperationsService.ReservationDetail reserve(CurrentAccessContext context, String orderId, long expected, String key, String correlation);
    WarehouseOperationsService.ReservationDetail release(CurrentAccessContext context, String reservationId, long expected, String key, String reason, String correlation, boolean expiry);
    WarehouseOperationsService.Page<WarehouseOperationsService.ReservationSummary> reservations(CurrentAccessContext context, String status, int page, int size);
    WarehouseOperationsService.ReservationDetail reservation(CurrentAccessContext context, String id);
    void expireReservations();
}
