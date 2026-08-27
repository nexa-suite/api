package com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem;

public record ProductPresentation(String value) {
	public ProductPresentation {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation("Product presentation is required");
		value = value.trim();
		if (value.length() > 160) throw new CatalogInvariantViolation("Product presentation exceeds 160 characters");
	}

	@Override
	public String toString() {
		return value;
	}
}
