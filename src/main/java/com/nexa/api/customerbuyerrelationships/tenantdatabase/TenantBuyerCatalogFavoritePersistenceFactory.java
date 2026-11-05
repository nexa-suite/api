package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerCatalogFavoritePersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-02 favorite persistence to the exact routed Tenant database session. */
@FunctionalInterface
public interface TenantBuyerCatalogFavoritePersistenceFactory {
    BuyerCatalogFavoritePersistencePort bindTo(JdbcTemplate tenantJdbc);
}
