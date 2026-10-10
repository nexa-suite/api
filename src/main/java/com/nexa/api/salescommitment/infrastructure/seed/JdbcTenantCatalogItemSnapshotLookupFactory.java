package com.nexa.api.salescommitment.infrastructure.seed;

import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.tenantdatabase.TenantCatalogItemSnapshotLookupFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Uses BC-04's existing mapper without resolving the central Catalog query bean. */
@Component
@Profile("!test")
public final class JdbcTenantCatalogItemSnapshotLookupFactory implements TenantCatalogItemSnapshotLookupFactory {
    @Override
    public CatalogItemSnapshotLookupPort bindTo(GetCatalogItemSnapshotUseCase tenantCatalogSnapshots) {
        return new CatalogItemSnapshotPersistenceAdapter(Objects.requireNonNull(tenantCatalogSnapshots,
                "Tenant Catalog snapshot query is required"));
    }
}
