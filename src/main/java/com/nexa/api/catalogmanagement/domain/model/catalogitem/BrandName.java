package com.nexa.api.catalogmanagement.domain.model.catalogitem;

public record BrandName(String value) {
	public BrandName {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation("Brand name is required");
		value = value.trim();
		if (value.length() > 120) throw new CatalogInvariantViolation("Brand name exceeds 120 characters");
	}

	@Override
	public String toString() {
		return value;
	}
}
