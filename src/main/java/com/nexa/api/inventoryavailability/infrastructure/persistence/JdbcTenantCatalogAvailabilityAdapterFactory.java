package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Binds the BC-05 availability adapter without constructing or querying BC-03 storage itself. */
@Component
@Profile("!test")
public final class JdbcTenantCatalogAvailabilityAdapterFactory implements TenantCatalogAvailabilityAdapterFactory {
	@Override
	public CatalogProductAvailabilityAdapter bindTo(JdbcTemplate tenantJdbc, SellableSkuQuery tenantSellableSkus) {
		return new CatalogProductAvailabilityAdapter(Objects.requireNonNull(tenantJdbc,
				"Tenant JDBC session is required"), Objects.requireNonNull(tenantSellableSkus,
						"Tenant Sellable SKU query is required"));
	}
}
