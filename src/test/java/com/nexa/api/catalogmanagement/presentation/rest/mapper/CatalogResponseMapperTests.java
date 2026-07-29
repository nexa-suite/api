package com.nexa.api.catalogmanagement.presentation.rest.mapper;

import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogResponseMapperTests {
	@Test
	void mapsApplicationProjectionToExplicitTransportShape() {
		var response = new CatalogResponseMapper().toSummary(new CatalogItemSummary("CAT-0001", "PROD-0001", "Queso",
				"Brand", "Dairy", "BOX 1", new BigDecimal("17.30"), "PEN", "REFRIGERATED", "/image.png", "image.png"));

		assertThat(response.catalogItemId()).isEqualTo("CAT-0001");
		assertThat(response.unitPrice().amount()).isEqualTo("17.30");
		assertThat(response.unitPrice().currency()).isEqualTo("PEN");
		assertThat(response.image().url()).isEqualTo("/image.png");
	}
}
