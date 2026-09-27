package com.nexa.api.inventoryavailability.application.publicapi;

import java.util.Optional;
import java.util.UUID;

/** Fulfillment lineage and conflict facts supplied to Inventory by the owner. */
public interface InventoryFulfillmentSource {
    boolean hasFulfillment(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    boolean hasActiveDispatch(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    Optional<UUID> physicalAllocationForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId);
}
