package com.nexa.api.catalogmanagement.presentation.rest.response;

public record CatalogItemSummaryResponse(String catalogItemId, String productId, String itemName, String brandName,
		String categoryName, String presentation, MoneyResponse unitPrice, String coldChainRequirement,
		CatalogMediaResponse image, String status, String availabilityStatus, boolean nearExpiry,
		String promotionLabel) {
	public CatalogItemSummaryResponse(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String presentation, MoneyResponse unitPrice, String coldChainRequirement,
			CatalogMediaResponse image) {
		this(catalogItemId, productId, itemName, brandName, categoryName, presentation, unitPrice, coldChainRequirement,
				image, "ACTIVE", "UNKNOWN", false, null);
	}
}
