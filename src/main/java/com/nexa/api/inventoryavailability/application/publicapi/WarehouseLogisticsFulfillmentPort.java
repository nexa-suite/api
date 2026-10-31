package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Cross-context boundary used by Logistics for reservation handoff. */
public interface WarehouseLogisticsFulfillmentPort {
    DispatchReservationSnapshot loadReservedReservation(String tenantId, String workspaceId,
                                                         String reservationId, long expectedVersion, Instant now);

    void ensureReservationReady(String tenantId, String workspaceId, String reservationId, Instant now);

    void consumeReservation(String tenantId, String workspaceId, String reservationId,
                            String actorMembershipId, String correlationId, Instant now);

    void releaseReservation(String tenantId, String workspaceId, String reservationId,
                            String actorMembershipId, String correlationId, String reason, Instant now);

    long countReadyReservations(String tenantId, String workspaceId, Instant now);

    /**
     * Returns the requested reservation/order pairs whose complete inventory
     * backing is contained in the caller's authorized warehouses. Empty inputs
     * fail closed.
     */
    Set<ReservationOrderReference> visibleReservationReferences(String tenantId, String workspaceId,
                                                                Set<ReservationOrderReference> reservationReferences,
                                                                Set<UUID> grantedWarehouseIds);

    record ReservationOrderReference(UUID reservationId, UUID salesOrderId) {
        public ReservationOrderReference {
            if (reservationId == null || salesOrderId == null) {
                throw new IllegalArgumentException("Reservation visibility reference is incomplete");
            }
        }
    }

    default long countReadyReservations(String tenantId, String workspaceId, Set<UUID> grantedWarehouseIds,
                                        Instant now) {
        return 0;
    }

    record DispatchReservationSnapshot(UUID reservationId, UUID salesOrderId, String orderNumber,
                                       UUID clientAccountId, String status, Instant expiresAt, long version,
                                       String destinationSnapshot, BigDecimal temperatureMin,
                                       BigDecimal temperatureMax, String temperatureUnit,
                                       String temperatureStatus) { }
}
