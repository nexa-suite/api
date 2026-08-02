package com.nexa.api.catalogmanagement.application.port.in;

import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;

public interface ListCatalogItemsUseCase {
	CatalogPage<CatalogItemSummary> list(CatalogSearchCriteria criteria);
	default CatalogPage<CatalogItemSummary> list(CatalogScope scope, CatalogSearchCriteria criteria) { return list(criteria); }
}
