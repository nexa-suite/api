package com.nexa.api.fulfillmentdelivery.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Explicit BC-06 read projection of the inventory-owned physical allocation. */
public final class PhysicalAllocationModels {
    private PhysicalAllocationModels() { }

    public record PhysicalAllocationView(UUID allocationId, String status, long version,
                                         Instant asOf, List<PhysicalAllocationLineView> lines) {
        public PhysicalAllocationView {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public record PhysicalAllocationLineView(UUID physicalAllocationLineId, UUID skuId, String catalogItemId,
                                             UUID warehouseId, UUID zoneId, UUID lotId,
                                             BigDecimal quantity, BigDecimal releasedQuantity,
                                             BigDecimal consumedQuantity, BigDecimal remainingQuantity,
                                             String unit, LocalDate expirationDate) { }
}
