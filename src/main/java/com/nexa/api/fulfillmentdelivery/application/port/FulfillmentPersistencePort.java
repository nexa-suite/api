package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.FulfillmentView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.LineView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.HandoffEvidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** BC-06 write/read port; SQL remains in the logistics adapter. */
public interface FulfillmentPersistencePort {
    FulfillmentView find(UUID tenantId, UUID workspaceId, UUID fulfillmentId);

    FulfillmentView findBySalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    FulfillmentView createAllocated(CreateRequest request);

    FulfillmentView transition(TransitionRequest request);

    FulfillmentView confirmPicking(PickingRequest request);

    ShortageResolutionResult resolveShortage(ShortageResolutionRequest request);

    FulfillmentView handOver(HandOverRequest request);

    Optional<FulfillmentView> findHandOverReplay(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                                  String idempotencyKey, String requestHash);

    Optional<HandoffEvidence> findHandoffEvidence(UUID tenantId, UUID workspaceId, UUID fulfillmentId);

    FulfillmentDriverAssignmentView assignDriver(AssignDriverRequest request);

    FulfillmentDriverAssignmentView changeDispatchPlan(DispatchPlanChangeRequest request);

    Optional<FulfillmentDriverAssignmentView> findDriverAssignment(UUID tenantId, UUID workspaceId,
                                                                    UUID fulfillmentId);

    Optional<FulfillmentDriverAssignmentView> findDriverAssignmentById(UUID tenantId, UUID workspaceId,
                                                                        UUID assignmentId);

    List<FulfillmentDriverAssignmentView> findDriverAssignmentHistory(UUID tenantId, UUID workspaceId,
                                                                      UUID fulfillmentId);

    record CreateRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                         UUID salesOrderId, UUID physicalAllocationId, String destinationSnapshot,
                         UUID actorMembershipId, String idempotencyKey, String requestHash,
                         Instant now, List<CreateLine> lines) { }

    record CreateLine(UUID id, UUID skuId, String catalogItemId, BigDecimal orderedQuantity,
                      BigDecimal backedQuantity, BigDecimal allocatedQuantity, String unit) { }

    record TransitionRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                             long expectedVersion, UUID actorMembershipId, String operation,
                             String targetStatus, String idempotencyKey, String requestHash,
                             String reason, Instant now) { }

    record PickingRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                          long expectedVersion, UUID actorMembershipId, UUID pickerIdentityId,
                          String idempotencyKey, String requestHash, Instant startedAt,
                          Instant completedAt, String notes, Long allocationVersion, List<PickedLine> lines) {
        public PickingRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                              long expectedVersion, UUID actorMembershipId, UUID pickerIdentityId,
                              String idempotencyKey, String requestHash, Instant startedAt,
                              Instant completedAt, String notes, List<PickedLine> lines) {
            this(tenantId, workspaceId, fulfillmentId, expectedVersion, actorMembershipId, pickerIdentityId,
                    idempotencyKey, requestHash, startedAt, completedAt, notes, null, lines);
        }
    }

    record PickedLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit,
                      UUID physicalAllocationLineId, UUID lotId, UUID warehouseId,
                      boolean fefoOverride, String fefoOverrideReason) {
        public PickedLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit) {
            this(fulfillmentLineId, skuId, quantity, unit, null, null, null, false, null);
        }
    }

    record ShortageResolutionRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                     long expectedVersion, UUID actorMembershipId,
                                     String idempotencyKey, String requestHash, String reason,
                                     Instant now, List<ShortageLine> lines) { }

    record ShortageLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit) { }

    record ShortageResolutionResult(FulfillmentView fulfillment, UUID resolutionId) { }

    record HandOverRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                           long expectedVersion, UUID actorMembershipId, String idempotencyKey,
                           String requestHash, Instant now, UUID driverAssignmentId,
                           long driverAssignmentVersion, UUID physicalAllocationId,
                           long physicalAllocationVersion, UUID outgoingGoodsCheckId) {
        public HandOverRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                               long expectedVersion, UUID actorMembershipId, String idempotencyKey,
                               String requestHash, Instant now) {
            this(tenantId, workspaceId, fulfillmentId, expectedVersion, actorMembershipId,
                    idempotencyKey, requestHash, now, null, -1, null, -1, null);
        }
    }

    record AssignDriverRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                               long expectedFulfillmentVersion, UUID expectedPhysicalAllocationId,
                               long expectedPhysicalAllocationVersion, long currentFulfillmentVersion,
                               String currentFulfillmentStatus, boolean currentReadiness,
                               UUID currentPhysicalAllocationId, long currentPhysicalAllocationVersion,
                               UUID responsibleMembershipId, UUID responsibleUserId,
                               String responsibleDisplayName, UUID actorMembershipId,
                               String idempotencyKey, String requestHash, Instant now) { }

    record DispatchPlanChangeRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                     long expectedFulfillmentVersion, UUID expectedAssignmentId,
                                     long expectedAssignmentVersion, UUID expectedPhysicalAllocationId,
                                     long expectedPhysicalAllocationVersion, long currentFulfillmentVersion,
                                     String currentFulfillmentStatus, boolean currentReadiness,
                                     UUID currentPhysicalAllocationId, long currentPhysicalAllocationVersion,
                                     UUID responsibleMembershipId, UUID responsibleUserId,
                                     String responsibleDisplayName, Instant plannedDispatchAt,
                                     UUID actorMembershipId, String idempotencyKey,
                                     String requestHash, Instant now) { }

    record FulfillmentDriverAssignmentView(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                                           UUID physicalAllocationId, long physicalAllocationVersion,
                                           UUID responsibleMembershipId, String responsibleDisplayName,
                                           UUID actorMembershipId, Instant assignedAt, Instant plannedDispatchAt,
                                           UUID deliveryId, boolean current) { }
}
