package com.nexa.api.catalogmanagement.application.port.in;

import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;

public interface GetCatalogItemUseCase {
	CatalogItemDetail getByCatalogItemId(String catalogItemId);
}
