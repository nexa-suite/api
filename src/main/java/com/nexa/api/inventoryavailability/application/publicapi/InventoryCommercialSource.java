package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Commercial facts supplied through Sales authority to an Inventory workflow. */
public interface InventoryCommercialSource {
    Optional<Snapshot> findCandidate(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    Optional<Snapshot> claimCandidate(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    record Snapshot(UUID id, String number, String status, long version, UUID clientAccountId,
                    UUID commercialCommitmentId, String destinationSnapshot, List<Line> lines) {
        public Snapshot { lines = List.copyOf(lines); }
    }
    record Line(UUID id, UUID skuId, String catalogItemId, BigDecimal quantity, String unit) { }
}
