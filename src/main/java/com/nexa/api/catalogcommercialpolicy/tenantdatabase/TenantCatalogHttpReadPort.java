package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Explicit Tenant-bound Catalog HTTP read path; it never falls back to central business storage. */
public interface TenantCatalogHttpReadPort {
    default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(CurrentAccessContext context, String kind, String search, int page, int size) {
        throw new UnsupportedOperationException("Catalog filter choices are unavailable");
    }

	CatalogPage<CatalogItemSummary> list(CurrentAccessContext accessContext, CatalogSearchCriteria criteria);

	CatalogItemDetail getByCatalogItemId(CurrentAccessContext accessContext, String catalogItemId);
}
