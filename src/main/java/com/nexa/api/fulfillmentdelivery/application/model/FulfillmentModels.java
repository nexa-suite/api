package com.nexa.api.fulfillmentdelivery.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FulfillmentModels {
    private FulfillmentModels() { }

    public record FulfillmentView(UUID id, UUID salesOrderId, UUID physicalAllocationId,
                                  String status, String destinationSnapshot, long version,
                                  Instant createdAt, Instant updatedAt, UUID deliveryId,
                                  String deliveryStatus, long deliveryVersion, List<LineView> lines) {
        public FulfillmentView {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    /** Frozen current facts required to dispatch prepared goods. Optional only for older clients. */
    public record DispatchRequest(UUID physicalAllocationId, Long physicalAllocationVersion,
                                  UUID driverAssignmentId, Long driverAssignmentVersion,
                                  UUID outgoingGoodsCheckId) {
        public boolean isComplete() {
            return physicalAllocationId != null && physicalAllocationVersion != null
                    && physicalAllocationVersion >= 0 && driverAssignmentId != null
                    && driverAssignmentVersion != null && driverAssignmentVersion >= 0
                    && outgoingGoodsCheckId != null;
        }

        public boolean isAbsent() {
            return physicalAllocationId == null && physicalAllocationVersion == null
                    && driverAssignmentId == null && driverAssignmentVersion == null
                    && outgoingGoodsCheckId == null;
        }
    }

    /** Immutable evidence that the Warehouse actor transferred prepared goods to the assigned driver. */
    public record HandoffEvidence(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                                  UUID deliveryId, UUID warehouseActorMembershipId,
                                  UUID driverAssignmentId, UUID driverMembershipId,
                                  UUID physicalAllocationId, long physicalAllocationVersion,
                                  UUID outgoingGoodsCheckId, Instant occurredAt, boolean current) { }

    public record LineView(UUID id, UUID skuId, String catalogItemId, BigDecimal orderedQuantity,
                           BigDecimal backedQuantity, BigDecimal allocatedQuantity,
                           BigDecimal pickedQuantity, BigDecimal packedQuantity,
                           BigDecimal stagedQuantity, BigDecimal dispatchedQuantity,
                           BigDecimal deliveredQuantity, BigDecimal rejectedQuantity,
                           BigDecimal cancelledQuantity, BigDecimal unfulfilledQuantity,
                           BigDecimal remainingQuantity,
                           String unit) { }

    public record DeliveryView(UUID id, UUID fulfillmentId, UUID salesOrderId, String status,
                               String destinationSnapshot, Instant scheduledAt, Instant dispatchedAt,
                               Instant deliveredAt, long version, List<AttemptView> attempts) {
        public DeliveryView { attempts = List.copyOf(attempts == null ? List.of() : attempts); }
    }

    public record AttemptView(UUID id, int attemptNumber, String outcome, String status,
                              String failureReason, String notes, Instant attemptedAt) { }

    public record DeliveryOutcomeResult(DeliveryView delivery, UUID attemptId, BigDecimal finalAdjustmentAmount,
                                        String adjustmentCurrency, UUID receivableId, UUID salesOrderId,
                                        boolean allCommercialQuantityResolved, boolean partial,
                                        List<RemainingLine> remainingLines) {
        public DeliveryOutcomeResult {
            remainingLines = List.copyOf(remainingLines == null ? List.of() : remainingLines);
        }
    }

    public record RemainingLine(UUID fulfillmentLineId, UUID skuId, String catalogItemId,
                                BigDecimal quantity, String unit) { }

    public record PodView(UUID id, UUID deliveryId, String status, String receiverName,
                          Instant capturedAt, Instant sealedAt, UUID photoEvidenceObjectId,
                          UUID signatureEvidenceObjectId, long deliveryVersion) { }

    public record TemperatureView(UUID id, UUID deliveryId, UUID lotId, BigDecimal temperatureCelsius,
                                  String unit, String source, String status, Instant recordedAt,
                                  long deliveryVersion) { }

    public record TemperatureEvidenceView(UUID id, String subjectType, UUID subjectId, UUID lotId,
                                          UUID warehouseId, BigDecimal value, String unit,
                                          Instant occurredAt, UUID actorMembershipId, String status,
                                          String source) { }
}
