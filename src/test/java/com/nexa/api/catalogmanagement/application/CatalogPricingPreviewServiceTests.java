package com.nexa.api.catalogmanagement.application;

import com.nexa.api.catalogmanagement.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPreviewPort;
import com.nexa.api.catalogmanagement.application.service.CatalogPricingPreviewService;
import com.nexa.api.catalogmanagement.domain.model.pricing.PromotionCandidate;
import com.nexa.api.catalogmanagement.domain.model.promotion.Promotion;
import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogPricingPreviewServiceTests {
	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
	private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	@Test
	void loadsRequestedProductsInOneBatchAndReturnsLineTotals() {
		CatalogPricingPreviewPort port = (scope, productIds, asOf) -> {
			assertThat(productIds).containsExactly(PRODUCT);
			return List.of(new CatalogPricingPreviewModels.PriceContext(PRODUCT, new BigDecimal("390"), "PEN",
					List.of(new PromotionCandidate(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "Ten percent", "TEN-PERCENT",
							Promotion.DiscountType.PERCENTAGE, new BigDecimal("10"), null, NOW.minusSeconds(60), NOW.plusSeconds(60),
							BigDecimal.ONE, Promotion.StackingPolicy.EXCLUSIVE, PromotionStatus.ACTIVE, 10, List.of(), List.of()))));
		};
		CatalogPricingPreviewService service = new CatalogPricingPreviewService(port, () -> { }, Clock.fixed(NOW, ZoneOffset.UTC));

		CatalogPricingPreviewModels.Result result = service.preview(new CatalogScope(UUID.randomUUID(), UUID.randomUUID()),
				new CatalogPricingPreviewModels.Request(List.of(new CatalogPricingPreviewModels.ItemRequest(PRODUCT, new BigDecimal("5"))), null));

		assertThat(result.items()).hasSize(1);
		CatalogPricingPreviewModels.ItemResult item = result.items().getFirst();
		assertThat(item.baseUnitPrice()).isEqualByComparingTo("390");
		assertThat(item.effectiveUnitPrice()).isEqualByComparingTo("351");
		assertThat(item.lineBaseTotal()).isEqualByComparingTo("1950");
		assertThat(item.lineEffectiveTotal()).isEqualByComparingTo("1755");
		assertThat(item.discountAmount()).isEqualByComparingTo("39");
		assertThat(item.appliedPromotions()).hasSize(1);
	}
}
