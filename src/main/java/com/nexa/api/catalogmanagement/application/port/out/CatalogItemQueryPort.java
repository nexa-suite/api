package com.nexa.api.catalogmanagement.application.port.out;

import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemId;

import java.util.Optional;

public interface CatalogItemQueryPort {
	CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria);

	Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId catalogItemId);
}
