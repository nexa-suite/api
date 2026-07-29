package com.nexa.api.catalogmanagement.application;

import com.nexa.api.catalogmanagement.application.exception.CatalogItemNotFoundException;
import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.application.model.CatalogSortField;
import com.nexa.api.catalogmanagement.application.model.SortDirection;
import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.application.service.CatalogQueryService;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogQueryServiceTests {
	@Test
	void delegatesApplicationModelsWithoutTransportTypes() {
		CatalogItemSummary summary = new CatalogItemSummary("CAT-0001", "PROD-0001", "First", "Brand", "Dairy",
				"BOX 1", new BigDecimal("17.30"), "PEN", "REFRIGERATED", "/image.png", "image.png");
		CatalogItemDetail detail = new CatalogItemDetail("CAT-0001", "PROD-0001", "First", "Brand", "Dairy",
				"Description", "BOX 1", new BigDecimal("17.30"), "PEN", "REFRIGERATED", "/image.png", "image.png");
		CatalogItemQueryPort port = new CatalogItemQueryPort() {
			public CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria) {
				return new CatalogPage<>(List.of(summary), criteria.page(), criteria.size(), 1, criteria.sortField(), criteria.sortDirection());
			}

			public Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId id) { return Optional.of(detail); }
		};
		CatalogQueryService service = new CatalogQueryService(port);

		assertThat(service.list(new CatalogSearchCriteria()).items()).singleElement()
				.satisfies(result -> assertThat(result.catalogItemId()).isEqualTo("CAT-0001"));
		assertThat(service.getByCatalogItemId("cat-0001").description()).isEqualTo("Description");
	}

	@Test
	void rejectsMissingItem() {
		CatalogQueryService service = new CatalogQueryService(new CatalogItemQueryPort() {
			public CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria) {
				return new CatalogPage<>(List.of(), 0, 20, 0, CatalogSortField.ITEM_NAME, SortDirection.ASC);
			}

			public Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId id) { return Optional.empty(); }
		});
		assertThatThrownBy(() -> service.getByCatalogItemId("CAT-9999")).isInstanceOf(CatalogItemNotFoundException.class);
	}

	@Test
	void validatesSearchDefaultsAndBounds() {
		CatalogSearchCriteria criteria = new CatalogSearchCriteria();
		assertThat(criteria.page()).isZero();
		assertThat(criteria.size()).isEqualTo(20);
		assertThat(criteria.sortField()).isEqualTo(CatalogSortField.ITEM_NAME);
		assertThat(criteria.sortDirection()).isEqualTo(SortDirection.ASC);
		assertThatThrownBy(() -> new CatalogSearchCriteria("x", null, null, null, -1, 20, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CatalogSearchCriteria("x", null, null, null, 0, 101, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CatalogSearchCriteria("x".repeat(121), null, null, null, 0, 20, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
