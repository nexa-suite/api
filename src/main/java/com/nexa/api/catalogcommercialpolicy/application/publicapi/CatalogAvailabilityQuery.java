package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only availability facts used to enrich Catalog-owned item views. */
public interface CatalogAvailabilityQuery {
    List<Snapshot> findForCatalogItems(UUID tenantId, UUID workspaceId, List<String> catalogItemIds);

    List<Snapshot> findForCatalogItems(UUID tenantId, UUID workspaceId, List<String> catalogItemIds,
            List<SellableSkuQuery.InventorySkuSnapshot> inventoryPolicies);

    record Snapshot(String catalogItemId, String status, boolean nearExpiry, Instant asOf,
                    BigDecimal sellableAvailability) { }
}
