package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSortField;
import com.nexa.api.catalogcommercialpolicy.application.model.SortDirection;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSeedLoader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCatalogItemQueryAdapterTests {
	private final SeedCatalogItemQueryAdapter adapter = new SeedCatalogItemQueryAdapter(
			new CatalogSeedLoader(new ObjectMapper()));

	@Test
	void returnsActiveSeedPageWithStableMetadata() {
		var page = adapter.search(new CatalogSearchCriteria());

		assertThat(page.items()).hasSize(20);
		assertThat(page.totalItems()).isEqualTo(50);
		assertThat(page.totalPages()).isEqualTo(3);
		assertThat(page.items()).allSatisfy(item -> assertThat(item.catalogItemId()).startsWith("CAT-"));
	}

	@Test
	void filtersCaseInsensitivelyAndSortsByPriceDescending() {
		var page = adapter.search(new CatalogSearchCriteria("queso", null, null, null, 0, 100,
				CatalogSortField.UNIT_PRICE, SortDirection.DESC));

		assertThat(page.totalItems()).isPositive();
		assertThat(page.items()).extracting(item -> item.itemName().toLowerCase())
				.allMatch(name -> name.contains("queso") || name.contains("cheese"));
		assertThat(page.items()).isSortedAccordingTo((left, right) -> right.unitPriceAmount().compareTo(left.unitPriceAmount()));
	}

	@Test
	void findsOnlyActiveCatalogDetails() {
		assertThat(adapter.findByCatalogItemId(new com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId("CAT-0001")))
				.isPresent().get().extracting(detail -> detail.catalogItemId()).isEqualTo("CAT-0001");
		assertThat(adapter.findByCatalogItemId(new com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId("CAT-9999")))
				.isEmpty();
	}
}
