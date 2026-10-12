package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;

/** Adapts an already Tenant-bound BC-03 snapshot query to BC-04's narrow snapshot port. */
@FunctionalInterface
public interface TenantCatalogItemSnapshotLookupFactory {
    CatalogItemSnapshotLookupPort bindTo(GetCatalogItemSnapshotUseCase tenantCatalogSnapshots);
}
