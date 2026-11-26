package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerCatalogFavoriteUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerCatalogFavoritePersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Enables Buyer favorites when explicitly enabled for the active Tenant router. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "tenant-cloud"})
@ConditionalOnProperty(prefix = "nexa.tenant-business.catalog-favorites", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundBuyerCatalogFavoriteConfiguration {
    @Bean
    BuyerCatalogFavoriteUseCase tenantBoundBuyerCatalogFavoriteUseCase(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantBuyerCatalogFavoritePersistenceFactory favorites,
            TenantCatalogDetailQueryFactory catalogDetails,
            TenantCatalogAvailabilityAdapterFactory catalogAvailability) {
        return new TenantBoundBuyerCatalogFavoriteUseCase(router, customerAccounts, favorites, catalogDetails,
                catalogAvailability);
    }
}
