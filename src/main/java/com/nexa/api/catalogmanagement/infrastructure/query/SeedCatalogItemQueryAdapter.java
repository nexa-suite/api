package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.application.model.CatalogSortField;
import com.nexa.api.catalogmanagement.application.model.SortDirection;
import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItem;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemId;
import com.nexa.api.catalogmanagement.infrastructure.seed.CatalogSeedLoader;
import com.nexa.api.catalogmanagement.infrastructure.seed.CatalogFamilySkuMappingLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@Profile("test")
public final class SeedCatalogItemQueryAdapter implements CatalogItemQueryPort {
	private final List<CatalogItem> items;
	private final CatalogItemProjectionMapper projectionMapper;

	public SeedCatalogItemQueryAdapter(CatalogSeedLoader seedLoader) {
		this(seedLoader, null);
	}

	@Autowired
	public SeedCatalogItemQueryAdapter(CatalogSeedLoader seedLoader, CatalogFamilySkuMappingLoader mappingLoader) {
		this.items = List.copyOf(Objects.requireNonNull(seedLoader, "Catalog seed loader is required").loadDomainCatalog());
		this.projectionMapper = new CatalogItemProjectionMapper(mappingLoader);
	}

	@Override
	public CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria) {
		CatalogSearchCriteria safeCriteria = Objects.requireNonNull(criteria, "Catalog search criteria is required");
		List<CatalogItem> filtered = items.stream()
				.filter(item -> item.status().name().equals("ACTIVE"))
				.filter(item -> containsAny(item, safeCriteria.query()))
				.filter(item -> matches(item.brandName().value(), safeCriteria.brand()))
				.filter(item -> matches(item.categoryName().value(), safeCriteria.category()))
				.filter(item -> safeCriteria.coldChainRequirement() == null
						|| item.coldChainRequirement() == safeCriteria.coldChainRequirement())
				.sorted(comparator(safeCriteria.sortField(), safeCriteria.sortDirection()))
				.toList();

		long totalItems = filtered.size();
		long offset = (long) safeCriteria.page() * safeCriteria.size();
		List<CatalogItem> pageItems = offset >= totalItems
				? List.of()
				: List.copyOf(new ArrayList<>(filtered.subList((int) offset,
						(int) Math.min(offset + safeCriteria.size(), totalItems))));
		return new CatalogPage<>(pageItems.stream().map(projectionMapper::toSummary).toList(), safeCriteria.page(), safeCriteria.size(), totalItems,
				safeCriteria.sortField(), safeCriteria.sortDirection());
	}

	@Override
	public Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId catalogItemId) {
		Objects.requireNonNull(catalogItemId, "Catalog item id is required");
		return items.stream().filter(item -> item.status().name().equals("ACTIVE"))
				.filter(item -> item.catalogItemId().equals(catalogItemId)).findFirst()
				.map(projectionMapper::toDetail);
	}

	private static boolean containsAny(CatalogItem item, String query) {
		return query == null || query.isBlank() || Stream.of(item.itemName().value(), item.brandName().value(),
				item.categoryName().value(), item.description().value()).anyMatch(value -> matches(value, query));
	}

	private static boolean matches(String value, String filter) {
		return filter == null || filter.isBlank() || value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
	}

	private static Comparator<CatalogItem> comparator(CatalogSortField field, SortDirection direction) {
		Comparator<CatalogItem> primary = switch (field) {
			case ITEM_NAME -> Comparator.comparing(item -> item.itemName().value(), String.CASE_INSENSITIVE_ORDER);
			case BRAND_NAME -> Comparator.comparing(item -> item.brandName().value(), String.CASE_INSENSITIVE_ORDER);
			case CATEGORY_NAME -> Comparator.comparing(item -> item.categoryName().value(), String.CASE_INSENSITIVE_ORDER);
			case UNIT_PRICE -> Comparator.comparing(item -> item.unitPrice().amount());
		};
		if (direction == SortDirection.DESC) primary = primary.reversed();
		return primary.thenComparing(item -> item.catalogItemId().value(), String.CASE_INSENSITIVE_ORDER);
	}

}
