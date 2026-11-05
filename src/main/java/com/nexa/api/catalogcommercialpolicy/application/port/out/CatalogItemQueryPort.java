package com.nexa.api.catalogcommercialpolicy.application.port.out;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.UUID;

public interface CatalogItemQueryPort {
    default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(CatalogScope scope, String kind, String search, int page, int size) {
        throw new UnsupportedOperationException("Catalog filter choices are unavailable");
    }

    default com.nexa.api.catalogcommercialpolicy.application.model.CatalogFilterOptions filterOptions(
            CatalogScope scope, String kind, String search, int page, int size, UUID familyId) {
        if (familyId != null) throw new UnsupportedOperationException("Family-scoped variant choices are unavailable");
        return filterOptions(scope, kind, search, page, size);
    }

	CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria);

	Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId catalogItemId);

    default Optional<CatalogItemDetail> findBySellableSkuId(CatalogScope scope, UUID sellableSkuId) {
        return Optional.empty();
    }

    /** Returns the currently visible offers among the requested SKUs; missing IDs are omitted. */
    default List<CatalogItemDetail> findBySellableSkuIds(CatalogScope scope, List<UUID> sellableSkuIds) {
        return sellableSkuIds == null ? List.of() : sellableSkuIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(id -> findBySellableSkuId(scope, id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

	default CatalogPage<CatalogItemSummary> search(CatalogScope scope, CatalogSearchCriteria criteria) {
		return search(criteria);
	}

	default Optional<CatalogItemDetail> findByCatalogItemId(CatalogScope scope, CatalogItemId catalogItemId) {
		return findByCatalogItemId(catalogItemId);
	}

	default Optional<CatalogItemDetail> findByCatalogItemId(CatalogScope scope, CatalogItemId catalogItemId,
			BigDecimal quantity) {
		return findByCatalogItemId(scope, catalogItemId);
	}

	default List<CatalogItemDetail> findByCatalogItemIds(CatalogScope scope, List<CatalogItemId> catalogItemIds) {
		return catalogItemIds == null ? List.of() : catalogItemIds.stream()
				.filter(java.util.Objects::nonNull)
				.distinct()
				.map(id -> findByCatalogItemId(scope, id).orElse(null))
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	default List<CatalogItemDetail> findByCatalogItemIds(CatalogScope scope, List<CatalogItemId> catalogItemIds,
			Map<String, BigDecimal> quantitiesByCatalogItemId) {
		return catalogItemIds == null ? List.of() : catalogItemIds.stream()
				.filter(java.util.Objects::nonNull)
				.distinct()
				.map(id -> findByCatalogItemId(scope, id,
						quantitiesByCatalogItemId == null ? null : quantitiesByCatalogItemId.get(id.value())).orElse(null))
				.filter(java.util.Objects::nonNull)
				.toList();
	}
}
