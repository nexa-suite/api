package com.nexa.api.catalogmanagement.application.model;

import java.util.Objects;
import java.util.UUID;

public record CatalogScope(UUID tenantId, UUID workspaceId, boolean buyerView, UUID clientAccountId,
                           String clientAccountSegment, String buyerTier) {
    public CatalogScope(UUID tenantId, UUID workspaceId) {
        this(tenantId, workspaceId, false, null, null, null);
    }

    public CatalogScope(UUID tenantId, UUID workspaceId, boolean buyerView) {
        this(tenantId, workspaceId, buyerView, null, null, null);
    }

    public CatalogScope(UUID tenantId, UUID workspaceId, boolean buyerView, UUID clientAccountId) {
        this(tenantId, workspaceId, buyerView, clientAccountId, null, null);
    }

    public CatalogScope {
        tenantId = Objects.requireNonNull(tenantId, "Catalog tenant id is required");
        workspaceId = Objects.requireNonNull(workspaceId, "Catalog workspace id is required");
    }
}
