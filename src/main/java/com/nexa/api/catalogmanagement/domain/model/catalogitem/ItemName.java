package com.nexa.api.catalogmanagement.domain.model.catalogitem;

public record ItemName(String value) {
	public ItemName {
		value = required(value, "Item name", 160);
	}

	private static String required(String value, String label, int maximum) {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation(label + " is required");
		String normalized = value.trim();
		if (normalized.isEmpty()) throw new CatalogInvariantViolation(label + " is required");
		if (normalized.length() > maximum) throw new CatalogInvariantViolation(label + " exceeds " + maximum + " characters");
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
