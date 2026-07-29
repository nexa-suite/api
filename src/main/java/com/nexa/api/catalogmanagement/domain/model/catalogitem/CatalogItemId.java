package com.nexa.api.catalogmanagement.domain.model.catalogitem;

public record CatalogItemId(String value) {
	public CatalogItemId {
		value = normalize(value, "Catalog item id");
		if (!value.startsWith("CAT-") || !value.matches("CAT-[A-Z0-9-]+")) {
			throw new CatalogInvariantViolation("Catalog item id must use CAT- prefix and safe characters");
		}
	}

	private static String normalize(String value, String label) {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation(label + " is required");
		String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
		if (normalized.length() > 64) throw new CatalogInvariantViolation(label + " exceeds 64 characters");
		if (!normalized.equals(value.trim().toUpperCase(java.util.Locale.ROOT))) {
			throw new CatalogInvariantViolation(label + " contains invalid characters");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
