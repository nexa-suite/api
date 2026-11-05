package com.nexa.api.catalogcommercialpolicy.application;

import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogItemNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSortField;
import com.nexa.api.catalogcommercialpolicy.application.model.SortDirection;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogQueryService;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

	@Test
	void resolvesMultipleSellableSkusThroughOneAuthorizedBatchQuery() {
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID firstSkuId = UUID.randomUUID();
		UUID secondSkuId = UUID.randomUUID();
		CatalogItemDetail first = new CatalogItemDetail("CAT-0001", "PROD-0001", "First", "Brand", "Dairy",
				"Description", "BOX 1", new BigDecimal("17.30"), "PEN", "REFRIGERATED", "/image.png", "image.png");
		CatalogItemDetail second = new CatalogItemDetail("CAT-0002", "PROD-0002", "Second", "Brand", "Dairy",
				"Description", "BOX 1", new BigDecimal("12.30"), "PEN", "REFRIGERATED", "/image.png", "image.png");
		AtomicInteger batchQueries = new AtomicInteger();
		AtomicInteger scalarQueries = new AtomicInteger();
		AtomicInteger authorizationChecks = new AtomicInteger();
		CatalogItemQueryPort port = new CatalogItemQueryPort() {
			@Override
			public CatalogPage<CatalogItemSummary> search(CatalogSearchCriteria criteria) {
				return new CatalogPage<>(List.of(), 0, 20, 0, CatalogSortField.ITEM_NAME, SortDirection.ASC);
			}

			@Override
			public Optional<CatalogItemDetail> findByCatalogItemId(CatalogItemId id) {
				return Optional.empty();
			}

			@Override
			public Optional<CatalogItemDetail> findBySellableSkuId(CatalogScope scope, UUID sellableSkuId) {
				scalarQueries.incrementAndGet();
				return Optional.empty();
			}

			@Override
			public List<CatalogItemDetail> findBySellableSkuIds(CatalogScope scope, List<UUID> sellableSkuIds) {
				batchQueries.incrementAndGet();
				assertThat(scope.tenantId()).isEqualTo(tenantId);
				assertThat(scope.workspaceId()).isEqualTo(workspaceId);
				assertThat(sellableSkuIds).containsExactly(firstSkuId, secondSkuId);
				return List.of(first, second);
			}
		};
		CatalogQueryService service = new CatalogQueryService(port, authorizationChecks::incrementAndGet);

		assertThat(service.findBySellableSkuIds(new CatalogScope(tenantId, workspaceId),
				List.of(firstSkuId, secondSkuId))).containsExactly(first, second);
		assertThat(batchQueries).hasValue(1);
		assertThat(scalarQueries).hasValue(0);
		assertThat(authorizationChecks).hasValue(1);
	}
}
