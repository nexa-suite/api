package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds Catalog's authoritative item snapshot query to one routed Tenant JDBC session. */
@FunctionalInterface
public interface TenantCatalogItemSnapshotQueryFactory {
    GetCatalogItemSnapshotUseCase bindTo(JdbcTemplate tenantJdbc, CatalogClientAccountPort tenantClientAccounts,
                                         ProductAvailabilityPort tenantAvailability);
}
