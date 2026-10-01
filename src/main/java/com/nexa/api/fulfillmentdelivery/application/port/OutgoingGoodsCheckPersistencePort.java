package com.nexa.api.fulfillmentdelivery.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** BC-06 persistence boundary for append-only outgoing-goods comparisons. */
public interface OutgoingGoodsCheckPersistencePort {
    PersistResult record(RecordRequest request);

    Optional<StoredCheck> latest(UUID tenantId, UUID workspaceId, UUID fulfillmentId);

    boolean hasOpenDiscrepancy(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                               UUID physicalAllocationId, long physicalAllocationVersion);

    boolean hasCurrentMatch(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                            long fulfillmentVersion, UUID physicalAllocationId,
                            long physicalAllocationVersion);

    record RecordRequest(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                        long fulfillmentVersion, UUID physicalAllocationId,
                        long physicalAllocationVersion, UUID actorMembershipId,
                        String idempotencyKey, String requestHash, boolean matches,
                        Instant checkedAt, List<LineFact> lines) {
        public RecordRequest {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    record LineFact(UUID physicalAllocationLineId, UUID skuId, UUID expectedLotId,
                    UUID observedLotId, BigDecimal expectedQuantity,
                    BigDecimal observedQuantity, String unit, boolean matches) { }

    record StoredCheck(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                       UUID physicalAllocationId, long physicalAllocationVersion,
                       boolean matches, UUID checkedByMembershipId, Instant checkedAt,
                       List<LineFact> lines) {
        public StoredCheck {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    record PersistResult(StoredCheck check, boolean replayed, boolean keyConflict) { }
}
