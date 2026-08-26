package com.nexa.api.catalogcommercialpolicy.application.model;

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
		CatalogPricingView pricing,
		String productFamilyId,
		String productFamilyCode,
		String productFamilyName,
		String sellableSkuId,
		String skuCode,
		String unitOfMeasure,
		String packagingType,
		BigDecimal netWeight,
		BigDecimal grossWeight,
		Instant availabilityAsOf,
		String productVariantCode,
		String productVariantName) {
	public CatalogItemDetail(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String description, String presentation, BigDecimal unitPriceAmount,
			String unitPriceCurrency, String coldChainRequirement, String imageUrl, String imageFileName,
			String status, String availabilityStatus, boolean nearExpiry, String promotionLabel,
			CatalogPricingView pricing) {
		this(catalogItemId, productId, itemName, brandName, categoryName, description, presentation,
				unitPriceAmount, unitPriceCurrency, coldChainRequirement, imageUrl, imageFileName, status,
				availabilityStatus, nearExpiry, promotionLabel, pricing, null, null, null, null, null, null,
				null, null, null, Instant.EPOCH, null, null);
	}

	public CatalogItemDetail(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String description, String presentation, BigDecimal unitPriceAmount,
			String unitPriceCurrency, String coldChainRequirement, String imageUrl, String imageFileName) {
		this(catalogItemId, productId, itemName, brandName, categoryName, description, presentation, unitPriceAmount,
				unitPriceCurrency, coldChainRequirement, imageUrl, imageFileName, "ACTIVE", "UNKNOWN", false, null,
				CatalogPricingView.base(unitPriceAmount, unitPriceCurrency, Instant.EPOCH), null, null, null, null, null,
				null, null, null, null, Instant.EPOCH, null, null);
	}

	public CatalogItemDetail(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String description, String presentation, BigDecimal unitPriceAmount,
			String unitPriceCurrency, String coldChainRequirement, String imageUrl, String imageFileName,
			String status, String availabilityStatus, boolean nearExpiry, String promotionLabel) {
		this(catalogItemId, productId, itemName, brandName, categoryName, description, presentation, unitPriceAmount,
				unitPriceCurrency, coldChainRequirement, imageUrl, imageFileName, status, availabilityStatus,
				nearExpiry, promotionLabel, CatalogPricingView.base(unitPriceAmount, unitPriceCurrency, Instant.EPOCH),
				null, null, null, null, null, null, null, null, null, Instant.EPOCH, null, null);
	}
}
