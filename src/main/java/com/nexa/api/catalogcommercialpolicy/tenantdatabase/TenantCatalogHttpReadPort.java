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

    default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(
            CurrentAccessContext context, String kind, String search, int page, int size, java.util.UUID familyId) {
        if (familyId != null) throw new UnsupportedOperationException("Family-scoped variant choices are unavailable");
        return filterOptions(context, kind, search, page, size);
    }

	CatalogPage<CatalogItemSummary> list(CurrentAccessContext accessContext, CatalogSearchCriteria criteria);

	CatalogItemDetail getByCatalogItemId(CurrentAccessContext accessContext, String catalogItemId);
}
