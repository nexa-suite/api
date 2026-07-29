package com.nexa.api.sales.domain;

import java.util.Locale;

/** Stable identity for a sales order. */
public record SalesOrderId(String value) {
	public SalesOrderId {
		value = normalize(value, "Sales order id");
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
