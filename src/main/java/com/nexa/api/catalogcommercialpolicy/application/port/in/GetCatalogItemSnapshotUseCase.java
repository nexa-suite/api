package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSnapshot;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Map;

public interface GetCatalogItemSnapshotUseCase {
    Optional<CatalogItemSnapshot> findActive(String catalogItemId, UUID tenantId, UUID workspaceId);

    default Optional<CatalogItemSnapshot> findActive(String catalogItemId, UUID tenantId,
                                                       UUID workspaceId, UUID customerAccountId) {
        return findActive(catalogItemId, tenantId, workspaceId);
    }

    default Optional<CatalogItemSnapshot> findActive(String catalogItemId, UUID tenantId, UUID workspaceId,
                                                       UUID customerAccountId, BigDecimal quantity) {
        return findActive(catalogItemId, tenantId, workspaceId, customerAccountId);
    }

    default List<CatalogItemSnapshot> findActive(List<String> catalogItemIds, UUID tenantId, UUID workspaceId) {
        return catalogItemIds == null ? List.of() : catalogItemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(id -> findActive(id, tenantId, workspaceId).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    default List<CatalogItemSnapshot> findActive(List<String> catalogItemIds, UUID tenantId,
                                                   UUID workspaceId, UUID customerAccountId) {
        return catalogItemIds == null ? List.of() : catalogItemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(id -> findActive(id, tenantId, workspaceId, customerAccountId).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    default List<CatalogItemSnapshot> findActive(List<String> catalogItemIds, UUID tenantId,
                                                   UUID workspaceId, UUID customerAccountId,
                                                   Map<String, BigDecimal> quantitiesByCatalogItemId) {
        return catalogItemIds == null ? List.of() : catalogItemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(id -> findActive(id, tenantId, workspaceId, customerAccountId,
                        quantitiesByCatalogItemId == null ? null : quantitiesByCatalogItemId.get(id)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
