package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

public interface ListCatalogItemsUseCase {
	CatalogPage<CatalogItemSummary> list(CatalogSearchCriteria criteria);
	default CatalogPage<CatalogItemSummary> list(CatalogScope scope, CatalogSearchCriteria criteria) { return list(criteria); }
}
