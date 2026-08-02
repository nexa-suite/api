package com.nexa.api.catalogmanagement.application.model;

import java.util.Objects;
import java.util.UUID;

public record CatalogScope(UUID tenantId, UUID workspaceId, boolean buyerView) {
    public CatalogScope(UUID tenantId, UUID workspaceId) {
        this(tenantId, workspaceId, false);
    }

    public CatalogScope {
        tenantId = Objects.requireNonNull(tenantId, "Catalog tenant id is required");
        workspaceId = Objects.requireNonNull(workspaceId, "Catalog workspace id is required");
    }
}
