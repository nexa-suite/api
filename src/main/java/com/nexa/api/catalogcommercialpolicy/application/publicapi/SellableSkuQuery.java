package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Data-only Catalog contract for active, visible, currently priced SKUs. */
public interface SellableSkuQuery {
    Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, UUID skuId);

    /**
     * Returns the catalog policy needed by inventory-owned physical validation.
     * This deliberately exposes no persistence type or catalog write capability.
     */
    Optional<SellableSkuPolicy> findPhysicalValidationPolicy(UUID tenantId, UUID workspaceId, UUID skuId);

    default Map<UUID, SellableSkuReference> findActive(
            UUID tenantId, UUID workspaceId, List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) return Map.of();
        Map<UUID, SellableSkuReference> result = new LinkedHashMap<>();
        skuIds.stream().filter(java.util.Objects::nonNull).distinct()
                .forEach(id -> findActive(tenantId, workspaceId, id).ifPresent(value -> result.put(id, value)));
        return Map.copyOf(result);
    }

    Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(
            UUID tenantId, UUID workspaceId, String legacyCatalogItemId);

    record SellableSkuReference(UUID skuId, UUID familyId, String familyCode, String skuCode,
                                String legacyCatalogItemId, String familyName, String presentation,
                                String unitOfMeasure, BigDecimal price, String currency) {
    }

    record SellableSkuPolicy(UUID skuId, String status, boolean visible,
                             BigDecimal temperatureMin, BigDecimal temperatureMax) {
    }
}
