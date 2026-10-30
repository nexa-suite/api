package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

public interface ListCatalogItemsUseCase {
    default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(CatalogScope scope, String kind, String search, int page, int size) {
        throw new UnsupportedOperationException("Catalog filter choices are unavailable");
    }

    default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(
            CatalogScope scope, String kind, String search, int page, int size, java.util.UUID familyId) {
        if (familyId != null) throw new UnsupportedOperationException("Family-scoped variant choices are unavailable");
        return filterOptions(scope, kind, search, page, size);
    }

	CatalogPage<CatalogItemSummary> list(CatalogSearchCriteria criteria);
	default CatalogPage<CatalogItemSummary> list(CatalogScope scope, CatalogSearchCriteria criteria) { return list(criteria); }
}
