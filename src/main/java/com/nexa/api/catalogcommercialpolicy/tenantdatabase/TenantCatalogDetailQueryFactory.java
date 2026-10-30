package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogAvailabilityQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.Optional;
import java.util.List;

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
		CatalogAvailabilityQuery bindTo(JdbcTemplate tenantJdbc, SellableSkuQuery tenantSellableSkus);
	}

	interface TenantCatalogReadQuery {
        default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(UUID tenantId, UUID workspaceId, String kind, String search, int page, int size) {
            throw new UnsupportedOperationException("Catalog filter choices are unavailable");
        }

        default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(
                UUID tenantId, UUID workspaceId, String kind, String search, int page, int size, UUID familyId) {
            if (familyId != null) throw new UnsupportedOperationException("Family-scoped variant choices are unavailable");
            return filterOptions(tenantId, workspaceId, kind, search, page, size);
        }

		CatalogItemDetail getByCatalogItemId(UUID tenantId, UUID workspaceId, String catalogItemId);

		Optional<CatalogItemDetail> findBySellableSkuId(UUID tenantId, UUID workspaceId, UUID sellableSkuId);

		List<CatalogItemDetail> findBySellableSkuIds(UUID tenantId, UUID workspaceId, List<UUID> sellableSkuIds);

		CatalogPage<CatalogItemSummary> list(UUID tenantId, UUID workspaceId, CatalogSearchCriteria criteria);
	}
}
