package com.nexa.api.catalogmanagement.presentation.rest.response;

public record CatalogItemDetailResponse(String catalogItemId, String productId, String itemName, String brandName,
		String categoryName, String description, String presentation, MoneyResponse unitPrice,
		String coldChainRequirement, CatalogMediaResponse image) { }
