package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import java.math.BigDecimal;

public record CatalogSeedItemRecord(
		String catalogItemId,
		String productId,
		String itemName,
		String brandName,
		String categoryName,
		String description,
		BigDecimal unitPriceAmount,
		String unitPriceCurrency,
		int availableStock,
		String coldChainRequirement,
		String imageUrl,
		String imageFileName,
		String presentation,
		String sourcePriceCode,
		String sourcePriceDescription) {
}
