package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogQueryService;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogItemSnapshotQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-03's owner query implementation with only caller-bound Tenant dependencies. */
@Component
@Profile("!test")
public final class JdbcTenantCatalogItemSnapshotQueryFactory implements TenantCatalogItemSnapshotQueryFactory {
    @Override
    public GetCatalogItemSnapshotUseCase bindTo(JdbcTemplate tenantJdbc,
            CatalogClientAccountPort tenantClientAccounts, ProductAvailabilityPort tenantAvailability) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        CatalogClientAccountPort accounts = Objects.requireNonNull(tenantClientAccounts,
                "Tenant Catalog account port is required");
        ProductAvailabilityPort availability = Objects.requireNonNull(tenantAvailability,
                "Tenant Catalog availability port is required");
        var offers = new JdbcAuthoritativeOfferQuery(jdbc, accounts);
        var query = new JdbcCatalogItemQueryAdapter(jdbc, availability, offers);
        return new CatalogQueryService(query);
    }
}
