package com.nexa.api.catalogmanagement.presentation.rest.response;

public record CatalogItemSummaryResponse(String catalogItemId, String productId, String itemName, String brandName,
		String categoryName, String presentation, MoneyResponse unitPrice, String coldChainRequirement,
		CatalogMediaResponse image) { }
