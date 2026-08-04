package com.nexa.api.catalogmanagement.presentation.rest.response;

import com.nexa.api.catalogmanagement.application.model.CatalogPricingView;
import java.time.Instant;
import java.util.List;

public record CatalogItemSummaryResponse(String catalogItemId, String productId, String itemName, String brandName,
		String categoryName, String presentation, MoneyResponse unitPrice, String coldChainRequirement,
		CatalogMediaResponse image, String status, String availabilityStatus, boolean nearExpiry,
			String promotionLabel, MoneyResponse basePrice, MoneyResponse effectivePrice,
			MoneyResponse discountAmount, String currency, List<CatalogAppliedPromotionResponse> appliedPromotions,
			Instant pricingAsOf, String productFamilyId, String productFamilyCode, String productFamilyName,
			String sellableSkuId, String skuCode, String unitOfMeasure, String packagingType,
			java.math.BigDecimal netWeight, java.math.BigDecimal grossWeight, Instant availabilityAsOf) {
	public CatalogItemSummaryResponse(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String presentation, MoneyResponse unitPrice, String coldChainRequirement,
			CatalogMediaResponse image, String status, String availabilityStatus, boolean nearExpiry,
			String promotionLabel, MoneyResponse basePrice, MoneyResponse effectivePrice,
			MoneyResponse discountAmount, String currency, List<CatalogAppliedPromotionResponse> appliedPromotions,
			Instant pricingAsOf) {
		this(catalogItemId, productId, itemName, brandName, categoryName, presentation, unitPrice, coldChainRequirement,
				image, status, availabilityStatus, nearExpiry, promotionLabel, basePrice, effectivePrice, discountAmount,
				currency, appliedPromotions, pricingAsOf, null, null, null, null, null, null, null, null, null, Instant.EPOCH);
	}
	public CatalogItemSummaryResponse(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String presentation, MoneyResponse unitPrice, String coldChainRequirement,
			CatalogMediaResponse image) {
		this(catalogItemId, productId, itemName, brandName, categoryName, presentation, unitPrice, coldChainRequirement,
				image, "ACTIVE", "UNKNOWN", false, null);
	}

	public CatalogItemSummaryResponse(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String presentation, MoneyResponse unitPrice, String coldChainRequirement,
			CatalogMediaResponse image, String status, String availabilityStatus, boolean nearExpiry,
			String promotionLabel) {
		this(catalogItemId, productId, itemName, brandName, categoryName, presentation, unitPrice, coldChainRequirement,
				image, status, availabilityStatus, nearExpiry, promotionLabel, unitPrice, unitPrice,
				new MoneyResponse("0", unitPrice == null ? null : unitPrice.currency()),
				unitPrice == null ? null : unitPrice.currency(), List.of(), Instant.EPOCH);
	}
}
