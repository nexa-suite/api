package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

public record CatalogItemSnapshot(String catalogItemId, String itemName, String presentation, PriceSnapshot price) {
	public CatalogItemSnapshot {
		if (catalogItemId == null || catalogItemId.isBlank() || itemName == null || itemName.isBlank() || presentation == null || presentation.isBlank() || price == null) throw new SalesInvariantViolation("Catalog snapshot is incomplete");
		catalogItemId = catalogItemId.trim(); itemName = itemName.trim(); presentation = presentation.trim();
	}
}
