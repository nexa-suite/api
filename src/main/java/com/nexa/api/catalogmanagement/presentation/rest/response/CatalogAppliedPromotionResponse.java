package com.nexa.api.catalogmanagement.presentation.rest.response;

import java.math.BigDecimal;

public record CatalogAppliedPromotionResponse(String id, String name, String discountType,
		BigDecimal discountAmount) { }
