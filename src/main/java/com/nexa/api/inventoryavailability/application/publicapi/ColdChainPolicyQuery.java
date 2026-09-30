package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** BC-05 temperature range projection for delivery evidence classification. */
public interface ColdChainPolicyQuery {
    Optional<Range> rangeForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId);

    Optional<Range> rangeForDeliveryAndLot(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId);

    /** Resolve a lot's warehouse-owned identity and cold-chain range without exposing BC-05 persistence. */
    Optional<LotTemperatureContext> temperatureContextForLot(UUID tenantId, UUID workspaceId, UUID lotId);

    /** A warehouse-wide range is available only when every configured active zone agrees. */
    Optional<Range> commonTemperatureRangeForWarehouse(UUID tenantId, UUID workspaceId, UUID warehouseId);

    boolean lotIsAllocatedToDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId);

    record Range(BigDecimal minimumCelsius, BigDecimal maximumCelsius, String unit) { }

    record LotTemperatureContext(UUID lotId, UUID warehouseId, UUID zoneId, Optional<Range> range) { }
}
