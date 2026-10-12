package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogAvailabilityQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Technical BC-05 binding for catalog availability queries within one Tenant transaction. */
@FunctionalInterface
public interface TenantCatalogAvailabilityAdapterFactory {
	CatalogAvailabilityQuery bindTo(JdbcTemplate tenantJdbc, SellableSkuQuery tenantSellableSkus);
}
