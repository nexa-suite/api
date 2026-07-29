package com.nexa.api.catalogmanagement.domain.model.catalogitem;

public record ProductId(String value) {
	public ProductId {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation("Product id is required");
		value = value.trim().toUpperCase(java.util.Locale.ROOT);
		if (value.length() > 64 || !value.startsWith("PROD-") || !value.matches("PROD-[A-Z0-9-]+")) {
			throw new CatalogInvariantViolation("Product id must use PROD- prefix and safe characters");
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
