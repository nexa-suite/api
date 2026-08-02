package com.nexa.api.catalogmanagement.application.port.in;

import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;

public interface GetCatalogItemUseCase {
	CatalogItemDetail getByCatalogItemId(String catalogItemId);
	default CatalogItemDetail getByCatalogItemId(CatalogScope scope, String catalogItemId) { return getByCatalogItemId(catalogItemId); }
}
