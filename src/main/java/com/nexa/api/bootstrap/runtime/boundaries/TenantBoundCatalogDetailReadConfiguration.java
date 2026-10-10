package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps tenant-routed catalog detail composition absent unless explicitly enabled. */
@Configuration(proxyBeanMethods = false)
public class TenantBoundCatalogDetailReadConfiguration {
	@Bean
	@Primary
	@ConditionalOnProperty(prefix = "nexa.tenant-business.catalog-detail-read", name = "enabled",
			havingValue = "true", matchIfMissing = false)
	TenantBoundCatalogDetailReader tenantBoundCatalogDetailReader(TenantBusinessDatabaseRouter router,
			TenantCustomerAccountQueryFactory customerAccounts,
			TenantCatalogDetailQueryFactory catalogDetails,
			TenantCatalogAvailabilityAdapterFactory catalogAvailability) {
		return new TenantBoundCatalogDetailReader(router, customerAccounts, catalogDetails, catalogAvailability);
	}
}
