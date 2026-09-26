package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/** Data-only Catalog contract for active, visible, currently priced SKUs. */
public interface SellableSkuQuery {
    Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, UUID skuId);

    default Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId,
                                                       UUID customerAccountId, UUID skuId) {
        return findActive(tenantId, workspaceId, skuId);
    }

    default Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId,
                                                       UUID customerAccountId, UUID skuId, BigDecimal quantity) {
        return findActive(tenantId, workspaceId, customerAccountId, skuId);
    }

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

    default Map<UUID, SellableSkuReference> findActive(UUID tenantId, UUID workspaceId,
                                                        UUID customerAccountId, List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) return Map.of();
        Map<UUID, SellableSkuReference> result = new LinkedHashMap<>();
        skuIds.stream().filter(java.util.Objects::nonNull).distinct()
                .forEach(id -> findActive(tenantId, workspaceId, customerAccountId, id)
                        .ifPresent(value -> result.put(id, value)));
        return Map.copyOf(result);
    }

    default Map<UUID, SellableSkuReference> findActive(UUID tenantId, UUID workspaceId,
                                                        UUID customerAccountId, Map<UUID, BigDecimal> quantities) {
        if (quantities == null || quantities.isEmpty()) return Map.of();
        Map<UUID, SellableSkuReference> result = new LinkedHashMap<>();
        quantities.forEach((id, quantity) -> {
            if (id != null) findActive(tenantId, workspaceId, customerAccountId, id, quantity)
                    .ifPresent(value -> result.put(id, value));
        });
        return Map.copyOf(result);
    }

    Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(
            UUID tenantId, UUID workspaceId, String legacyCatalogItemId);

    default Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(UUID tenantId, UUID workspaceId,
                                                                            UUID customerAccountId,
                                                                            String legacyCatalogItemId) {
        return findActiveByLegacyCatalogItemId(tenantId, workspaceId, legacyCatalogItemId);
    }

    record SellableSkuReference(UUID skuId, UUID familyId, String familyCode, String skuCode,
                                String legacyCatalogItemId, String familyName, String presentation,
                                String unitOfMeasure, BigDecimal price, String currency,
                                BigDecimal basePrice, BigDecimal discountAmount, Instant pricingAsOf) {
        public SellableSkuReference(UUID skuId, UUID familyId, String familyCode, String skuCode,
                                    String legacyCatalogItemId, String familyName, String presentation,
                                    String unitOfMeasure, BigDecimal price, String currency) {
            this(skuId, familyId, familyCode, skuCode, legacyCatalogItemId, familyName, presentation,
                    unitOfMeasure, price, currency, price, BigDecimal.ZERO, Instant.EPOCH);
        }
    }

    record SellableSkuPolicy(UUID skuId, String status, boolean visible,
                             BigDecimal temperatureMin, BigDecimal temperatureMax) {
    }
}
