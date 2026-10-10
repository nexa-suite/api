package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Technical BC-05 binding for catalog availability queries within one Tenant transaction. */
@FunctionalInterface
public interface TenantCatalogAvailabilityAdapterFactory {
	ProductAvailabilityPort bindTo(JdbcTemplate tenantJdbc, SellableSkuQuery tenantSellableSkus);
}
