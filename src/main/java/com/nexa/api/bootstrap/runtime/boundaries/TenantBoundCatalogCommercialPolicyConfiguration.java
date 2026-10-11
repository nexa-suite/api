package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestCompositionFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.RegionalCurrencyQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Opt-in production composition for BC-03 operations on the verified Tenant database. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class TenantBoundCatalogCommercialPolicyConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "nexa.tenant-business.catalog-management", name = "enabled",
            havingValue = "true", matchIfMissing = false)
    TenantCatalogCommercialPolicyRequestPort tenantCatalogCommercialPolicyRequestPort(
            TenantBusinessDatabaseRouter router, TenantCustomerAccountQueryFactory customerAccounts,
            TenantSellableSkuQueryFactory sellableSkus,
            TenantCatalogAvailabilityAdapterFactory availabilityAdapters,
            TenantCatalogCommercialPolicyRequestCompositionFactory compositions,
            RegionalCurrencyQuery regionalCurrency) {
        return new TenantBoundCatalogCommercialPolicyRequestAdapter(router, customerAccounts, sellableSkus,
                availabilityAdapters, compositions, regionalCurrency);
    }

}
