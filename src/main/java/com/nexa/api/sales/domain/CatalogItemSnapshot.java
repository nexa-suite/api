package com.nexa.api.sales.domain;

public record CatalogItemSnapshot(String catalogItemId, String itemName, String presentation, PriceSnapshot price) {
	public CatalogItemSnapshot { if (catalogItemId == null || itemName == null || presentation == null || price == null) throw new IllegalArgumentException("Catalog snapshot is required"); }
}
