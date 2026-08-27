package com.nexa.api.inventoryavailability.application.port;

import java.math.BigDecimal;
import java.time.Instant;
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

    record DispatchReservationSnapshot(UUID reservationId, UUID salesOrderId, String orderNumber,
                                       UUID clientAccountId, String status, Instant expiresAt, long version,
                                       String destinationSnapshot, BigDecimal temperatureMin,
                                       BigDecimal temperatureMax, String temperatureUnit,
                                       String temperatureStatus) { }
}
