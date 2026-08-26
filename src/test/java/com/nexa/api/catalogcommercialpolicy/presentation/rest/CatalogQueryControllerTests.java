package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.ListCatalogItemsUseCase;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.mapper.CatalogResponseMapper;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.request.CatalogQueryParameters;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogQueryControllerTests {
	@Test
	void listsThroughInputPortAndMapsResponse() {
		ListCatalogItemsUseCase listUseCase = mock(ListCatalogItemsUseCase.class);
		GetCatalogItemUseCase getUseCase = mock(GetCatalogItemUseCase.class);
		when(listUseCase.list(any())).thenReturn(new CatalogPage<>(List.of(summary()), 0, 20, 1,
				com.nexa.api.catalogcommercialpolicy.application.model.CatalogSortField.ITEM_NAME,
				com.nexa.api.catalogcommercialpolicy.application.model.SortDirection.ASC));
		CatalogQueryController controller = new CatalogQueryController(listUseCase, getUseCase, new CatalogResponseMapper());

		CatalogQueryParameters parameters = new CatalogQueryParameters();
		parameters.setQ("queso");
		var response = controller.list(parameters);

		assertThat(response.items()).hasSize(1);
		assertThat(response.items().getFirst().catalogItemId()).isEqualTo("CAT-0001");
		verify(listUseCase).list(any());
	}

	@Test
	void translatesApplicationNotFoundToSharedApiNotFound() {
		ListCatalogItemsUseCase listUseCase = mock(ListCatalogItemsUseCase.class);
		GetCatalogItemUseCase getUseCase = mock(GetCatalogItemUseCase.class);
		when(getUseCase.getByCatalogItemId("CAT-9999")).thenThrow(new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogItemNotFoundException("CAT-9999"));
		CatalogQueryController controller = new CatalogQueryController(listUseCase, getUseCase, new CatalogResponseMapper());

		assertThatThrownBy(() -> controller.getById("CAT-9999"))
				.isInstanceOf(com.nexa.api.shared.presentation.error.ApiResourceNotFoundException.class);
	}

	private static CatalogItemSummary summary() {
		return new CatalogItemSummary("CAT-0001", "PROD-0001", "Queso", "Brand", "Dairy", "BOX 1",
				new BigDecimal("17.30"), "PEN", "REFRIGERATED", "/image.png", "image.png");
	}
}
