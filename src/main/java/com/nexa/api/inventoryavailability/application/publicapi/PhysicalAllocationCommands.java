package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory-owned physical responsibility boundary.  A commercial backing
 * remains an availability fact; this contract selects the concrete lots and
 * consumes the backing responsibility in the same transaction.
 */
public interface PhysicalAllocationCommands {
    AllocationResult getByFulfillment(UUID tenantId, UUID workspaceId, UUID fulfillmentId);

    AllocationResult allocate(AllocationRequest request);

    AllocationResult consumeForDispatch(ConsumeRequest request);

    /**
     * Releases the physically reserved quantity that a picker could not
     * pick. The allocation remains open until dispatch consumes the picked
     * quantity, so an incomplete pick can never consume stock by accident.
     */
    AllocationResult reconcileUnpicked(ReconcileUnpickedRequest request);

    void release(ReleaseRequest request);

    record AllocationRequest(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                             UUID commercialCommitmentId, UUID inventoryBackingId,
                             UUID fulfillmentId, UUID allocationId, UUID actorMembershipId,
                             String idempotencyKey, String requestHash, List<RequestedLine> lines,
                             Instant now) {
        public AllocationRequest {
            if (tenantId == null || workspaceId == null || salesOrderId == null
                    || commercialCommitmentId == null || inventoryBackingId == null
                    || fulfillmentId == null || allocationId == null || actorMembershipId == null
                    || idempotencyKey == null || idempotencyKey.isBlank()
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                    || lines == null || lines.isEmpty() || now == null) {
                throw new IllegalArgumentException("Physical allocation request is incomplete");
            }
            idempotencyKey = idempotencyKey.trim();
            lines = List.copyOf(lines);
        }
    }

    record RequestedLine(UUID skuId, String catalogItemId, BigDecimal quantity, String unit) {
        public RequestedLine {
            if (skuId == null || catalogItemId == null || catalogItemId.isBlank()
                    || quantity == null || quantity.signum() <= 0 || unit == null || unit.isBlank()) {
                throw new IllegalArgumentException("Physical allocation line is incomplete");
            }
            catalogItemId = catalogItemId.trim();
            unit = unit.trim();
        }
    }

    record ConsumeRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                          UUID actorMembershipId, String idempotencyKey, String requestHash,
                          long expectedVersion, Instant now) { }

    record ReleaseRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                          UUID actorMembershipId, String idempotencyKey, String requestHash,
                          long expectedVersion, String reason, Instant now) { }

    record ReconcileUnpickedRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                   UUID actorMembershipId, String idempotencyKey, String requestHash,
                                   long expectedVersion, String reason, List<UnpickedLine> lines,
                                   Instant now) {
        public ReconcileUnpickedRequest {
            if (tenantId == null || workspaceId == null || fulfillmentId == null || actorMembershipId == null
                    || idempotencyKey == null || idempotencyKey.isBlank()
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                    || lines == null || lines.isEmpty() || now == null) {
                throw new IllegalArgumentException("Unpicked reconciliation request is incomplete");
            }
            idempotencyKey = idempotencyKey.trim();
            reason = reason == null ? "Unpicked quantity reconciled" : reason.trim();
            lines = List.copyOf(lines);
        }
    }

    record UnpickedLine(UUID fulfillmentLineId, UUID skuId, String catalogItemId,
                        BigDecimal quantity, String unit) {
        public UnpickedLine {
            if (fulfillmentLineId == null || skuId == null || catalogItemId == null || catalogItemId.isBlank()
                    || quantity == null || quantity.signum() <= 0 || unit == null || unit.isBlank()) {
                throw new IllegalArgumentException("Unpicked line is incomplete");
            }
            catalogItemId = catalogItemId.trim();
            unit = unit.trim();
        }
    }

    record AllocationResult(UUID allocationId, UUID inventoryBackingId, String status,
                            List<Line> lines, long version) {
        public AllocationResult { lines = List.copyOf(lines == null ? List.of() : lines); }
    }

    record Line(UUID skuId, String catalogItemId, UUID warehouseId, UUID zoneId, UUID lotId,
                BigDecimal quantity, BigDecimal releasedQuantity, BigDecimal consumedQuantity,
                String unit, java.time.LocalDate expirationDate) { }
}
