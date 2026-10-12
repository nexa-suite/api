package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogAvailabilityQuery;

import java.util.List;
import java.util.Objects;

/** Adapts the narrow Inventory availability contract to Catalog's internal output port. */
final class CatalogAvailabilityPortAdapter implements ProductAvailabilityPort {
    private final CatalogAvailabilityQuery availability;

    CatalogAvailabilityPortAdapter(CatalogAvailabilityQuery availability) {
        this.availability = Objects.requireNonNull(availability, "Catalog availability query is required");
    }

    @Override
    public List<ProductAvailabilityPort.Snapshot> find(CatalogScope scope, List<String> catalogItemIds) {
        CatalogScope requestedScope = Objects.requireNonNull(scope, "Catalog scope is required");
        return availability.findForCatalogItems(requestedScope.tenantId(), requestedScope.workspaceId(), catalogItemIds)
                .stream().map(CatalogAvailabilityPortAdapter::snapshot).toList();
    }

    @Override
    public List<ProductAvailabilityPort.Snapshot> findWithPolicies(CatalogScope scope, List<String> catalogItemIds,
            List<com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery.InventorySkuSnapshot> policies) {
        CatalogScope requestedScope = Objects.requireNonNull(scope, "Catalog scope is required");
        return availability.findForCatalogItems(requestedScope.tenantId(), requestedScope.workspaceId(),
                        catalogItemIds, policies)
                .stream().map(CatalogAvailabilityPortAdapter::snapshot).toList();
    }

    private static ProductAvailabilityPort.Snapshot snapshot(CatalogAvailabilityQuery.Snapshot value) {
        return new ProductAvailabilityPort.Snapshot(value.catalogItemId(), value.status(), value.nearExpiry(),
                value.asOf(), value.sellableAvailability());
    }
}
