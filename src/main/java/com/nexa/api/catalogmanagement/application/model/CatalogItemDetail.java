package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;

public record CatalogItemDetail(
		String catalogItemId,
		String productId,
		String itemName,
		String brandName,
		String categoryName,
		String description,
		String presentation,
		BigDecimal unitPriceAmount,
		String unitPriceCurrency,
		String coldChainRequirement,
		String imageUrl,
		String imageFileName) {
}
