package com.nexa.api.catalogcommercialpolicy.application;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogPricingPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogProductPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogTaxonomyPort;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPricingService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogProductService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPromotionService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogTaxonomyService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CatalogPermissionServiceTests {
	private static final CatalogScope SCOPE = new CatalogScope(UUID.randomUUID(), UUID.randomUUID(), false);

	@Test
	void productCommandsAndQueriesUseCatalogPermissions() {
		CatalogAuthorizationPort authorization = mock(CatalogAuthorizationPort.class);
		CatalogProductService service = new CatalogProductService(mock(CatalogProductPort.class), authorization);

		service.products(SCOPE, 0, 25, null, null);
		service.createProduct(SCOPE, "CAT-0001", "PROD-0001", "cheese", "Cheese", "Description",
				UUID.randomUUID(), UUID.randomUUID(), "REFRIGERATED", "BOX 1", "UNIT", true, "/cheese.webp");

		verify(authorization).require(CatalogPermissions.READ);
		verify(authorization).require(CatalogPermissions.MANAGE);
	}

	@Test
	void taxonomyCommandsAndQueriesUseCatalogPermissions() {
		CatalogAuthorizationPort authorization = mock(CatalogAuthorizationPort.class);
		CatalogTaxonomyService service = new CatalogTaxonomyService(mock(CatalogTaxonomyPort.class), authorization);

		service.categories(SCOPE, 0, 25, null);
		service.createCategory(SCOPE, null, "dairy", "Dairy", null);

		verify(authorization).require(CatalogPermissions.READ);
		verify(authorization).require(CatalogPermissions.MANAGE);
	}

	@Test
	void pricingCommandsAndQueriesUseReadAndPriceManagePermissions() {
		CatalogAuthorizationPort authorization = mock(CatalogAuthorizationPort.class);
		CatalogPricingService service = new CatalogPricingService(mock(CatalogPricingPort.class), authorization);

		service.history(SCOPE, UUID.randomUUID());
		service.create(SCOPE, UUID.randomUUID(), new BigDecimal("10.50"), "PEN", Instant.parse("2026-01-01T00:00:00Z"),
				null, "SEED", "Initial price");

		verify(authorization).require(CatalogPermissions.READ);
		verify(authorization).require(CatalogPermissions.PRICE_MANAGE);
	}

	@Test
	void promotionCommandsAndQueriesUsePromotionPermissions() {
		CatalogAuthorizationPort authorization = mock(CatalogAuthorizationPort.class);
		CatalogPromotionService service = new CatalogPromotionService(mock(CatalogPromotionPort.class), authorization);

		service.promotions(SCOPE, 0, 25, null);
		service.create(SCOPE, "summer", "Summer", null, "PERCENTAGE", BigDecimal.TEN, null,
				Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), BigDecimal.ONE,
				"EXCLUSIVE", List.of(), List.of());

		verify(authorization).require(CatalogPermissions.PROMOTION_READ);
		verify(authorization).require(CatalogPermissions.PROMOTION_MANAGE);
	}
}
