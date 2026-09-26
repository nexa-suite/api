package com.nexa.api.catalogcommercialpolicy.presentation.rest.mapper;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogResponseMapperTests {
	@Test
	void mapsApplicationProjectionToExplicitTransportShape() {
		var response = new CatalogResponseMapper().toSummary(new CatalogItemSummary("CAT-0001", "PROD-0001", "Queso",
				"Brand", "Dairy", "BOX 1", new BigDecimal("17.30"), "PEN", "REFRIGERATED", "/image.png", "image.png",
				"ACTIVE", "AVAILABLE", false, null,
				CatalogPricingView.base(new BigDecimal("17.30"), "PEN", Instant.EPOCH), null, null, null, null, null,
				null, null, null, null, Instant.EPOCH, null, null, new BigDecimal("6.5")));

		assertThat(response.catalogItemId()).isEqualTo("CAT-0001");
		assertThat(response.unitPrice().amount()).isEqualTo("17.30");
		assertThat(response.unitPrice().currency()).isEqualTo("PEN");
		assertThat(response.image().url()).isEqualTo("/image.png");
		assertThat(response.sellableAvailability()).isEqualByComparingTo("6.5");
	}

	@Test
	void buyerProjectionHidesRawBasePriceAndNamesCurrentOfferPrice() {
		var pricing = new CatalogPricingView(new BigDecimal("20.00"), new BigDecimal("17.00"),
				new BigDecimal("3.00"), "PEN", List.of(), Instant.EPOCH, true);
		var item = new CatalogItemSummary("CAT-0001", "PROD-0001", "Queso", "Brand", "Dairy", "BOX 1",
				new BigDecimal("17.00"), "PEN", "REFRIGERATED", "/image.png", "image.png", "ACTIVE", "AVAILABLE",
				false, null, pricing, null, null, null, null, null, null, null, null, null, Instant.EPOCH, null, null,
				new BigDecimal("6.5"));

		var response = new CatalogResponseMapper().toSummary(item);

		assertThat(response.basePrice()).isNull();
		assertThat(response.currentOfferPrice().amount()).isEqualTo("17");
		assertThat(response.effectivePrice().amount()).isEqualTo("17");
	}
}
