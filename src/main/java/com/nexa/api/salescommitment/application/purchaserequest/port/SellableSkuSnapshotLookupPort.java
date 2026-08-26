package com.nexa.api.salescommitment.application.purchaserequest.port;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** ACL for immutable SKU facts needed by Sales snapshots. */
public interface SellableSkuSnapshotLookupPort {
    Optional<Snapshot> findActive(UUID skuId, UUID tenantId, UUID workspaceId);

    default Map<UUID, Snapshot> findActive(List<UUID> skuIds, UUID tenantId, UUID workspaceId) {
        Map<UUID, Snapshot> result = new LinkedHashMap<>();
        if (skuIds != null) {
            for (UUID skuId : skuIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
                findActive(skuId, tenantId, workspaceId).ifPresent(snapshot -> result.put(skuId, snapshot));
            }
        }
        return Map.copyOf(result);
    }

    record Snapshot(UUID skuId, UUID familyId, String familyCode, String skuCode, String legacyCatalogItemId,
                    String familyName, String presentation, BigDecimal price, String currency) { }
}
