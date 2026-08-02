package com.nexa.api.catalogmanagement.presentation.rest.response;

public record CatalogItemDetailResponse(String catalogItemId, String productId, String itemName, String brandName,
		String categoryName, String description, String presentation, MoneyResponse unitPrice,
		String coldChainRequirement, CatalogMediaResponse image, String status, String availabilityStatus,
		boolean nearExpiry, String promotionLabel) {
	public CatalogItemDetailResponse(String catalogItemId, String productId, String itemName, String brandName,
			String categoryName, String description, String presentation, MoneyResponse unitPrice,
			String coldChainRequirement, CatalogMediaResponse image) {
		this(catalogItemId, productId, itemName, brandName, categoryName, description, presentation, unitPrice,
				coldChainRequirement, image, "ACTIVE", "UNKNOWN", false, null);
	}
}
