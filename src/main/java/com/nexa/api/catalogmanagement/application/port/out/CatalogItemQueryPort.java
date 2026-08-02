package com.nexa.api.catalogmanagement.application.port.out;

import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemId;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;

import java.util.Optional;

public interface CatalogItemQueryPort {
	CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria);

	Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId catalogItemId);

	default CatalogPage<CatalogItemSummary> search(CatalogScope scope, CatalogSearchCriteria criteria) {
		return search(criteria);
	}

	default Optional<CatalogItemDetail> findByCatalogItemId(CatalogScope scope, CatalogItemId catalogItemId) {
		return findByCatalogItemId(catalogItemId);
	}
}
