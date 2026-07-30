package com.nexa.api.warehouse.domain;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Exact non-negative stock quantity. No unit conversion is performed here;
 * callers must compare quantities with the same normalized unit.
 */
public record Quantity(BigDecimal value, String unit) {
	public Quantity {
		if (value == null) {
			throw new IllegalArgumentException("Quantity value is required");
		}
		if (value.signum() < 0) {
			throw new IllegalArgumentException("Quantity value cannot be negative");
		}
		unit = normalizeUnit(unit);
	}

	public static Quantity of(BigDecimal value, String unit) {
		return new Quantity(value, unit);
	}

	private static String normalizeUnit(String unit) {
		if (unit == null || unit.isBlank()) {
			throw new IllegalArgumentException("Quantity unit is required");
		}
		String normalized = unit.trim().toUpperCase(Locale.ROOT);
		if (normalized.length() > 16 || !normalized.matches("[A-Z][A-Z0-9_-]*")) {
			throw new IllegalArgumentException("Quantity unit contains invalid characters");
		}
		return normalized;
	}
}
