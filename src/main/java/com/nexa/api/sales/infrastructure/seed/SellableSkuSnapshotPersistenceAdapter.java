package com.nexa.api.sales.infrastructure.seed;

import com.nexa.api.sales.application.purchaserequest.port.SellableSkuSnapshotLookupPort;
import com.nexa.api.catalogmanagement.application.publicapi.SellableSkuQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Reads one canonical SKU plus its current server price for Sales-owned snapshots. */
@Component
@Profile("!test")
public class SellableSkuSnapshotPersistenceAdapter implements SellableSkuSnapshotLookupPort {
    private final SellableSkuQuery catalog;

    public SellableSkuSnapshotPersistenceAdapter(SellableSkuQuery catalog) { this.catalog = catalog; }

    @Override
    public Optional<Snapshot> findActive(UUID skuId, UUID tenantId, UUID workspaceId) {
        return catalog.findActive(tenantId, workspaceId, skuId)
                .map(value -> new Snapshot(value.skuId(), value.familyId(), value.familyCode(), value.skuCode(),
                        value.legacyCatalogItemId(), value.familyName(), value.presentation(), value.price(), value.currency()));
    }

    @Override
    public Map<UUID, Snapshot> findActive(List<UUID> skuIds, UUID tenantId, UUID workspaceId) {
        return catalog.findActive(tenantId, workspaceId, skuIds).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> snapshot(entry.getValue())));
    }

    private static Snapshot snapshot(SellableSkuQuery.SellableSkuReference value) {
        return new Snapshot(value.skuId(), value.familyId(), value.familyCode(), value.skuCode(),
                value.legacyCatalogItemId(), value.familyName(), value.presentation(), value.price(), value.currency());
    }
}
