package com.nexa.api.warehouse.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Published-language read boundary for warehouse facts consumed by workflow events. */
public interface WarehouseEventContextQueryPort {
    Optional<ReservationSnapshot> findActiveReservationForSalesOrder(UUID tenantId, UUID workspaceId,
                                                                       UUID salesOrderId);

    Optional<ReservationSnapshot> findReservationForSalesOrder(UUID tenantId, UUID workspaceId,
                                                                UUID salesOrderId);

    Optional<ReservationSnapshot> findReservation(UUID tenantId, UUID workspaceId, UUID reservationId);

    record ReservationSnapshot(UUID id, UUID salesOrderId, String status, long version) {
        public ReservationSnapshot {
            if (id == null || salesOrderId == null || status == null) {
                throw new IllegalArgumentException("Warehouse reservation snapshot is incomplete");
            }
            if (version < 0) throw new IllegalArgumentException("Warehouse reservation version cannot be negative");
        }
    }
}
