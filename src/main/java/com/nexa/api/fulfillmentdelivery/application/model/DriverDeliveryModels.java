package com.nexa.api.fulfillmentdelivery.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Server-authorized delivery projection for the currently assigned driver. */
public final class DriverDeliveryModels {
    private DriverDeliveryModels() { }

    public record DeliveryView(UUID id, UUID fulfillmentId, UUID salesOrderId, String status,
                               String destinationSnapshot, Instant scheduledAt, Instant dispatchedAt,
                               Instant deliveredAt, Instant updatedAt, long version, AttemptView activeAttempt,
                               List<OutcomeLineView> outcomeLines, ArrivalFact arrival) {
        public DeliveryView {
            outcomeLines = List.copyOf(outcomeLines == null ? List.of() : outcomeLines);
        }
    }

    public record OutcomeLineView(UUID fulfillmentLineId, UUID skuId, String catalogItemId,
                                  BigDecimal dispatchedQuantity, BigDecimal deliveredQuantity,
                                  BigDecimal rejectedQuantity, BigDecimal cancelledQuantity,
                                  BigDecimal remainingQuantity, String unit) { }

    public record AttemptView(UUID id, int attemptNumber, String status,
                              UUID startedByMembershipId, Instant startedAt) { }

    public record AttemptStartResult(DeliveryView delivery, AttemptView attempt, boolean replayed) { }

    public record AttemptStartRequest(UUID tenantId, UUID workspaceId, UUID deliveryId,
                                     UUID actorMembershipId, long expectedVersion,
                                     String idempotencyKey, String requestHash, Instant startedAt) { }

    public record ArrivalView(UUID id, UUID deliveryId, UUID attemptId, UUID actorMembershipId,
                              Instant arrivedAt, long deliveryVersion, boolean replayed) { }

    public record ArrivalFact(UUID id, UUID attemptId, Instant arrivedAt) { }

    public record ArrivalRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID attemptId,
                                 UUID actorMembershipId, long expectedVersion, String idempotencyKey,
                                 String requestHash, Instant arrivedAt) { }
}
