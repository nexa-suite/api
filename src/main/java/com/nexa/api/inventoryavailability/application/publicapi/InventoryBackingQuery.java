package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Narrow BC-05 availability snapshot consumed by the fulfillment boundary. */
public interface InventoryBackingQuery {
    Optional<Snapshot> findByCommitment(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId);

    record Snapshot(UUID id, UUID commercialCommitmentId, String status, List<Position> positions) {
        public Snapshot { positions = List.copyOf(positions == null ? List.of() : positions); }
    }

    record Position(UUID skuId, String catalogItemId, String unit, UUID warehouseId,
                    BigDecimal quantity) { }
}
