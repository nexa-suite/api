package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery.SellableSkuPolicy;

/** BC-05 temperature range projection for delivery evidence classification. */
public interface ColdChainPolicyQuery {
    Optional<Range> rangeForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId);

    Optional<Range> rangeForDeliveryAndLot(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId);

    /** Resolve a lot's warehouse-owned identity and cold-chain range without exposing BC-05 persistence. */
    Optional<LotTemperatureContext> temperatureContextForLot(UUID tenantId, UUID workspaceId, UUID lotId);

    /** Current Catalog temperature requirements for a SKU; empty only when the SKU is unavailable. */
    Optional<SellableSkuPolicy> temperatureRequirementForSku(UUID tenantId, UUID workspaceId, UUID skuId);

    /** A warehouse-wide range is available only when every configured active zone agrees. */
    Optional<Range> commonTemperatureRangeForWarehouse(UUID tenantId, UUID workspaceId, UUID warehouseId);

    boolean lotIsAllocatedToDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId);

    record Range(BigDecimal minimumCelsius, BigDecimal maximumCelsius, String unit) { }

    record LotTemperatureContext(UUID lotId, UUID warehouseId, UUID zoneId, Optional<Range> range, UUID skuId,
                                 long version, String inventoryLotStatus, boolean temperatureHoldOpen) {
        public LotTemperatureContext(UUID lotId, UUID warehouseId, UUID zoneId, Optional<Range> range) {
            this(lotId, warehouseId, zoneId, range, null, 0, null, false);
        }

        public LotTemperatureContext(UUID lotId, UUID warehouseId, UUID zoneId, Optional<Range> range, UUID skuId) {
            this(lotId, warehouseId, zoneId, range, skuId, 0, null, false);
        }

        public LotTemperatureContext(UUID lotId, UUID warehouseId, UUID zoneId, Optional<Range> range, UUID skuId,
                                     long version) {
            this(lotId, warehouseId, zoneId, range, skuId, version, null, false);
        }
    }
}
