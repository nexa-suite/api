package com.nexa.api.fulfillmentdelivery.application.publicapi;

import java.util.Optional;
import java.util.UUID;

/** Fulfillment-owned lineage facts; Inventory retains allocation and lot authority. */
public interface FulfillmentInventoryQuery {
    boolean hasFulfillment(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    boolean hasActiveDispatch(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    Optional<UUID> physicalAllocationForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId);
}
