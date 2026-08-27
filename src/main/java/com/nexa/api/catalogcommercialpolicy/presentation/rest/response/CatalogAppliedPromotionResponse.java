package com.nexa.api.catalogcommercialpolicy.presentation.rest.response;

import java.math.BigDecimal;

public record CatalogAppliedPromotionResponse(String id, String name, String discountType,
		BigDecimal discountAmount) { }
