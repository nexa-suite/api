package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;

public record CatalogItemSummary(
		String catalogItemId,
		String productId,
		String itemName,
		String brandName,
		String categoryName,
		String presentation,
		BigDecimal unitPriceAmount,
		String unitPriceCurrency,
		String coldChainRequirement,
		String imageUrl,
		String imageFileName) {
}
