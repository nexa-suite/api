package com.nexa.api.catalogmanagement.domain.model.catalogitem;

public record CategoryName(String value) {
	public CategoryName {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation("Category name is required");
		value = value.trim();
		if (value.length() > 80) throw new CatalogInvariantViolation("Category name exceeds 80 characters");
	}

	@Override
	public String toString() {
		return value;
	}
}
