package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record CatalogItemSnapshot(String catalogItemId, String itemName, String presentation, PriceSnapshot price) {
	public CatalogItemSnapshot {
		if (catalogItemId == null || catalogItemId.isBlank() || itemName == null || itemName.isBlank() || presentation == null || presentation.isBlank() || price == null) throw new SalesInvariantViolation("Catalog snapshot is incomplete");
		catalogItemId = catalogItemId.trim(); itemName = itemName.trim(); presentation = presentation.trim();
	}
}
