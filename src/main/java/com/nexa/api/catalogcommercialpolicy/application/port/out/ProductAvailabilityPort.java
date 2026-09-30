package com.nexa.api.catalogcommercialpolicy.application.port.out;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

@org.springframework.modulith.NamedInterface("catalog-availability-source")
public interface ProductAvailabilityPort {
    List<Snapshot> find(CatalogScope scope, List<String> catalogItemIds);

    /** Reuses the Catalog owner's facts already selected for this batch. */
    default List<Snapshot> findWithPolicies(CatalogScope scope, List<String> catalogItemIds,
            List<com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery.InventorySkuSnapshot> policies) {
        return find(scope, catalogItemIds);
    }

    record Snapshot(String catalogItemId, String status, boolean nearExpiry, Instant asOf,
                    BigDecimal sellableAvailability) {
        public Snapshot(String catalogItemId, String status, boolean nearExpiry, Instant asOf) {
            this(catalogItemId, status, nearExpiry, asOf, null);
        }
    }
}
