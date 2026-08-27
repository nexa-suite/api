package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** BC-05 temperature range projection for delivery evidence classification. */
public interface ColdChainPolicyQuery {
    Optional<Range> rangeForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId);

    Optional<Range> rangeForDeliveryAndLot(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId);

    boolean lotIsAllocatedToDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId);

    record Range(BigDecimal minimumCelsius, BigDecimal maximumCelsius, String unit) { }
}
