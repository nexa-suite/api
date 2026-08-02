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
		String imageFileName,
		String status,
		String availabilityStatus,
		boolean nearExpiry,
		String promotionLabel) {
	public CatalogItemSummary(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String presentation, BigDecimal unitPriceAmount, String unitPriceCurrency,
			String coldChainRequirement, String imageUrl, String imageFileName) {
		this(catalogItemId, productId, itemName, brandName, categoryName, presentation, unitPriceAmount,
				unitPriceCurrency, coldChainRequirement, imageUrl, imageFileName, "ACTIVE", "UNKNOWN", false, null);
	}
}
