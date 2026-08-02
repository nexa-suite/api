package com.nexa.api.catalogmanagement.application.port.in;

import com.nexa.api.catalogmanagement.application.model.CatalogItemSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface GetCatalogItemSnapshotUseCase {
    Optional<CatalogItemSnapshot> findActive(String catalogItemId, UUID tenantId, UUID workspaceId);
}
