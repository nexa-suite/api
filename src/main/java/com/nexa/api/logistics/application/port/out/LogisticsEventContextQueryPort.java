package com.nexa.api.logistics.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Published-language read boundary for logistics facts consumed by workflow events. */
public interface LogisticsEventContextQueryPort {
    Optional<DispatchSnapshot> findDispatch(UUID tenantId, UUID workspaceId, UUID dispatchOrderId);

    Optional<DispatchSnapshot> findDispatchByReservation(UUID tenantId, UUID workspaceId, UUID reservationId);

    record DispatchSnapshot(UUID id, UUID reservationId, UUID salesOrderId, UUID clientAccountId, long version) {
        public DispatchSnapshot {
            if (id == null || reservationId == null || salesOrderId == null || clientAccountId == null) {
                throw new IllegalArgumentException("Logistics dispatch snapshot is incomplete");
            }
            if (version < 0) throw new IllegalArgumentException("Logistics dispatch version cannot be negative");
        }
    }
}
