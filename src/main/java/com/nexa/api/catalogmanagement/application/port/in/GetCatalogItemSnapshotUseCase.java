package com.nexa.api.catalogmanagement.application.port.in;

import com.nexa.api.catalogmanagement.application.model.CatalogItemSnapshot;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface GetCatalogItemSnapshotUseCase {
    Optional<CatalogItemSnapshot> findActive(String catalogItemId, UUID tenantId, UUID workspaceId);

    default List<CatalogItemSnapshot> findActive(List<String> catalogItemIds, UUID tenantId, UUID workspaceId) {
        return catalogItemIds == null ? List.of() : catalogItemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(id -> findActive(id, tenantId, workspaceId).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
