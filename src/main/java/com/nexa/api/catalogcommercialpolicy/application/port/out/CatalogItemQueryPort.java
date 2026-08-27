package com.nexa.api.catalogcommercialpolicy.application.port.out;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

import java.util.Optional;
import java.util.List;

public interface CatalogItemQueryPort {
	CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria);

	Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId catalogItemId);

	default CatalogPage<CatalogItemSummary> search(CatalogScope scope, CatalogSearchCriteria criteria) {
		return search(criteria);
	}

	default Optional<CatalogItemDetail> findByCatalogItemId(CatalogScope scope, CatalogItemId catalogItemId) {
		return findByCatalogItemId(catalogItemId);
	}

	default List<CatalogItemDetail> findByCatalogItemIds(CatalogScope scope, List<CatalogItemId> catalogItemIds) {
		return catalogItemIds == null ? List.of() : catalogItemIds.stream()
				.filter(java.util.Objects::nonNull)
				.distinct()
				.map(id -> findByCatalogItemId(scope, id).orElse(null))
				.filter(java.util.Objects::nonNull)
				.toList();
	}
}
