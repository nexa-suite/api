package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

public interface GetCatalogItemUseCase {
	CatalogItemDetail getByCatalogItemId(String catalogItemId);
	default CatalogItemDetail getByCatalogItemId(CatalogScope scope, String catalogItemId) { return getByCatalogItemId(catalogItemId); }
}
