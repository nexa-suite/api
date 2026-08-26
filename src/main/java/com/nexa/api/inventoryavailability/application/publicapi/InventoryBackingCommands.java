package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory-owned availability decision used by Sales Commitment.
 *
 * The command records deterministic warehouse backing positions but does not
 * create a fulfillment/lot reservation. Those are separate downstream facts.
 */
public interface InventoryBackingCommands {
    BackingResult establish(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId,
                            List<RequestedLine> lines, Instant now);

    void release(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId, String reason, Instant now);

    record RequestedLine(UUID skuId, String catalogItemId, BigDecimal quantity, String unit) {
        public RequestedLine {
            if (skuId == null || catalogItemId == null || catalogItemId.isBlank()
                    || quantity == null || quantity.signum() <= 0 || unit == null || unit.isBlank()) {
                throw new IllegalArgumentException("Inventory backing line is incomplete");
            }
            catalogItemId = catalogItemId.trim();
            unit = unit.trim();
        }
    }

    record BackingResult(UUID backingId, List<Position> positions) {
        public BackingResult { positions = List.copyOf(positions == null ? List.of() : positions); }
    }

    record Position(UUID skuId, UUID warehouseId, BigDecimal quantity) { }
}
