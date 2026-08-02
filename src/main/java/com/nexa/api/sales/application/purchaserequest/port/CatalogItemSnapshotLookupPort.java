package com.nexa.api.sales.application.purchaserequest.port;

import com.nexa.api.sales.domain.model.purchaserequest.CatalogItemSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface CatalogItemSnapshotLookupPort {
	Optional<CatalogItemSnapshot> findActive(String catalogItemId);
	default Optional<CatalogItemSnapshot> findActive(String catalogItemId, UUID tenantId, UUID workspaceId) {
		return findActive(catalogItemId);
	}
}
