package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;
import java.time.Instant;

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
		String imageFileName,
		String status,
		String availabilityStatus,
		boolean nearExpiry,
		String promotionLabel,
		CatalogPricingView pricing) {
	public CatalogItemDetail(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String description, String presentation, BigDecimal unitPriceAmount,
			String unitPriceCurrency, String coldChainRequirement, String imageUrl, String imageFileName) {
		this(catalogItemId, productId, itemName, brandName, categoryName, description, presentation, unitPriceAmount,
				unitPriceCurrency, coldChainRequirement, imageUrl, imageFileName, "ACTIVE", "UNKNOWN", false, null,
				CatalogPricingView.base(unitPriceAmount, unitPriceCurrency, Instant.EPOCH));
	}

	public CatalogItemDetail(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String description, String presentation, BigDecimal unitPriceAmount,
			String unitPriceCurrency, String coldChainRequirement, String imageUrl, String imageFileName,
			String status, String availabilityStatus, boolean nearExpiry, String promotionLabel) {
		this(catalogItemId, productId, itemName, brandName, categoryName, description, presentation, unitPriceAmount,
				unitPriceCurrency, coldChainRequirement, imageUrl, imageFileName, status, availabilityStatus,
				nearExpiry, promotionLabel, CatalogPricingView.base(unitPriceAmount, unitPriceCurrency, Instant.EPOCH));
	}
}
