package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inventory-owned discrepancy observations; these facts never change stock. */
public interface InboundReceivingDiscrepancyCases {
    CaseFact create(CreateRequest request);

    CaseFact submit(SubmitRequest request);

    record CreateRequest(
            UUID tenantId,
            UUID workspaceId,
            UUID warehouseId,
            UUID expectedSkuId,
            UUID observedSkuId,
            String expectedBatchReference,
            String observedBatchReference,
            BigDecimal expectedQuantity,
            BigDecimal observedQuantity,
            String unit,
            String reason,
            String observationNotes,
            UUID actorMembershipId,
            String idempotencyKey,
            String requestHash,
            Instant recordedAt) {
        public CreateRequest {
            if (tenantId == null || workspaceId == null || warehouseId == null || observedSkuId == null
                    || expectedQuantity == null || expectedQuantity.signum() < 0
                    || observedQuantity == null || observedQuantity.signum() < 0
                    || unit == null || unit.isBlank() || unit.length() > 32
                    || reason == null || reason.isBlank() || reason.length() > 2_000
                    || (expectedBatchReference != null && expectedBatchReference.length() > 160)
                    || (observedBatchReference != null && observedBatchReference.length() > 160)
                    || (observationNotes != null && observationNotes.length() > 2_000)
                    || actorMembershipId == null || idempotencyKey == null || idempotencyKey.isBlank()
                    || idempotencyKey.length() > 160 || requestHash == null
                    || !requestHash.matches("[0-9a-f]{64}") || recordedAt == null) {
                throw new IllegalArgumentException("Inbound discrepancy facts are incomplete");
            }
        }
    }

    record SubmitRequest(
            UUID tenantId,
            UUID workspaceId,
            UUID caseId,
            UUID evidenceObjectId,
            UUID actorMembershipId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash,
            Instant submittedAt) {
        public SubmitRequest {
            if (tenantId == null || workspaceId == null || caseId == null || evidenceObjectId == null
                    || actorMembershipId == null || expectedVersion < 0
                    || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}") || submittedAt == null) {
                throw new IllegalArgumentException("Inbound discrepancy submission is incomplete");
            }
        }
    }

    record CaseFact(
            UUID id,
            UUID warehouseId,
            UUID expectedSkuId,
            UUID observedSkuId,
            String expectedBatchReference,
            String observedBatchReference,
            BigDecimal expectedQuantity,
            BigDecimal observedQuantity,
            String unit,
            String reason,
            String observationNotes,
            String status,
            UUID evidenceObjectId,
            long version,
            UUID recordedByMembershipId,
            Instant recordedAt,
            UUID submittedByMembershipId,
            Instant submittedAt,
            boolean replayed) { }
}
