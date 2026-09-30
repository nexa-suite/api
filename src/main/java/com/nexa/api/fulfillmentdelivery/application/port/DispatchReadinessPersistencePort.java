package com.nexa.api.fulfillmentdelivery.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** BC-06-owned fulfillment and picking facts used by the current readiness projection. */
public interface DispatchReadinessPersistencePort {
    List<PreparedFulfillment> candidates(UUID tenantId, UUID workspaceId);

    Optional<PreparedFulfillment> find(UUID tenantId, UUID workspaceId, UUID fulfillmentId);

    List<PickingEvidence> pickingEvidence(UUID tenantId, UUID workspaceId, UUID fulfillmentId);

    record PreparedFulfillment(UUID id,
                              UUID salesOrderId,
                              UUID physicalAllocationId,
                              String status,
                              long version,
                              UUID deliveryId,
                              String deliveryStatus,
                              Long deliveryVersion,
                              List<FulfillmentLine> lines) {
        public PreparedFulfillment {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    record FulfillmentLine(UUID id,
                           UUID skuId,
                           String catalogItemId,
                           BigDecimal allocatedQuantity,
                           BigDecimal pickedQuantity,
                           String unit) { }

    record PickingEvidence(UUID fulfillmentLineId,
                           String resultStatus,
                           BigDecimal quantity,
                           UUID physicalAllocationLineId,
                           UUID lotId,
                           UUID warehouseId) { }
}
