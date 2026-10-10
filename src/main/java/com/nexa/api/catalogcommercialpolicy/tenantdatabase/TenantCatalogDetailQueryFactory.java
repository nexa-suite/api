package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Technical BC-03 composition seam for catalog detail and paginated reads on a verified Tenant transaction.
 * BC-03 owns catalog/offer SQL; the caller supplies only its same-session availability
 * adapter binding and a Buyer profile already resolved by Customer & Buyer Relationships.
 */
public interface TenantCatalogDetailQueryFactory {
	/** Checks the Catalog owner permission before runtime queries an upstream Buyer profile. */
	void requireCatalogRead();

	TenantCatalogReadQuery bindTo(JdbcTemplate tenantJdbc,
			CatalogClientAccountPort.ClientAccountProfile buyerProfile,
			TenantAvailabilityAdapterFactory availabilityFactory);

	@FunctionalInterface
	interface TenantAvailabilityAdapterFactory {
		ProductAvailabilityPort bindTo(JdbcTemplate tenantJdbc, SellableSkuQuery tenantSellableSkus);
	}

	interface TenantCatalogReadQuery {
		CatalogItemDetail getByCatalogItemId(UUID tenantId, UUID workspaceId, String catalogItemId);

		CatalogPage<CatalogItemSummary> list(UUID tenantId, UUID workspaceId, CatalogSearchCriteria criteria);
	}
}
