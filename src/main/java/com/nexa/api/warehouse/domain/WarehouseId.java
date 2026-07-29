package com.nexa.api.warehouse.domain;

import java.util.Locale;

/** Stable identity for a physical warehouse. */
public record WarehouseId(String value) {
	public WarehouseId {
		value = normalize(value, "Warehouse id");
	}

	private static String normalize(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " is required");
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (normalized.length() > 64) {
			throw new IllegalArgumentException(label + " exceeds 64 characters");
		}
		if (!normalized.matches("[A-Z0-9-]+")) {
			throw new IllegalArgumentException(label + " contains invalid characters");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
