package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Unfiltered Catalog facts referenced by immutable Sales line snapshots. */
public interface CatalogDocumentSourceQuery {
    Map<UUID, Sku> skus(UUID tenantId, UUID workspaceId, List<UUID> skuIds);

    Map<UUID, String> familyNames(UUID tenantId, UUID workspaceId, List<UUID> familyIds);

    record Sku(UUID id, UUID familyId, String skuCode, BigDecimal grossWeight) { }
}
