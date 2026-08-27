package com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem;

public record CatalogDescription(String value) {
	public CatalogDescription {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation("Catalog description is required");
		value = value.trim();
		if (value.length() > 500) throw new CatalogInvariantViolation("Catalog description exceeds 500 characters");
	}

	@Override
	public String toString() {
		return value;
	}
}
