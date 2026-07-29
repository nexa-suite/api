package com.nexa.api.catalogmanagement.application.port.in;

import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;

public interface ListCatalogItemsUseCase {
	CatalogPage<CatalogItemSummary> list(CatalogSearchCriteria criteria);
}
