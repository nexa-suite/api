package com.nexa.api.sales.application.purchaserequest.port;

import com.nexa.api.sales.domain.model.purchaserequest.CatalogItemSnapshot;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

public interface CatalogItemSnapshotLookupPort {
	Optional<CatalogItemSnapshot> findActive(String catalogItemId);
	default Optional<CatalogItemSnapshot> findActive(String catalogItemId, UUID tenantId, UUID workspaceId) {
		return findActive(catalogItemId);
	}

	default List<CatalogItemSnapshot> findActive(List<String> catalogItemIds, UUID tenantId, UUID workspaceId) {
		return catalogItemIds == null ? List.of() : catalogItemIds.stream()
				.filter(id -> id != null && !id.isBlank())
				.distinct()
				.map(id -> findActive(id, tenantId, workspaceId).orElse(null))
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	default Map<String, CatalogItemSnapshot> findActiveById(List<String> catalogItemIds, UUID tenantId, UUID workspaceId) {
		Map<String, CatalogItemSnapshot> result = new LinkedHashMap<>();
		for (CatalogItemSnapshot snapshot : findActive(catalogItemIds, tenantId, workspaceId)) {
			result.put(snapshot.catalogItemId(), snapshot);
		}
		return Map.copyOf(result);
	}
}
