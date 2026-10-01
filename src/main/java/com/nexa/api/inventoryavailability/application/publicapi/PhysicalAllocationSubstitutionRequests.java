package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inventory-owned immutable proposals to review a physical allocation lot. */
public interface PhysicalAllocationSubstitutionRequests {
    Result request(Request request);

    record Request(UUID tenantId, UUID workspaceId, UUID fulfillmentId, UUID allocationId,
                   UUID physicalAllocationLineId, UUID expectedLotId, UUID alternativeLotId,
                   BigDecimal quantity, String unit, String reason, UUID actorMembershipId,
                   String idempotencyKey, String requestHash, long expectedAllocationVersion,
                   Instant requestedAt) {
        public Request {
            if (tenantId == null || workspaceId == null || fulfillmentId == null || allocationId == null
                    || physicalAllocationLineId == null || expectedLotId == null || alternativeLotId == null
                    || expectedLotId.equals(alternativeLotId) || quantity == null || quantity.signum() <= 0
                    || unit == null || unit.isBlank() || unit.length() > 32 || !unit.equals(unit.trim())
                    || unit.chars().anyMatch(Character::isISOControl) || reason == null || reason.isBlank()
                    || reason.length() > 2_000 || !reason.equals(reason.trim())
                    || reason.chars().anyMatch(Character::isISOControl) || actorMembershipId == null
                    || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                    || expectedAllocationVersion < 0 || requestedAt == null) {
                throw new IllegalArgumentException("Lot substitution request is incomplete");
            }
            unit = unit.trim();
            idempotencyKey = idempotencyKey.trim();
        }
    }

    record RequestFact(UUID id, UUID expectedLotId, UUID alternativeLotId, BigDecimal quantity,
                       String unit, String reason, String status, long currentAllocationVersion,
                       Instant recordedAt) { }

    record Result(RequestFact fact, boolean replayed) { }
}
