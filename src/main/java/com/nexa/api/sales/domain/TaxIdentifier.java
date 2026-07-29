package com.nexa.api.sales.domain;

public record TaxIdentifier(String countryCode, String type, String value) {
	public TaxIdentifier {
		countryCode = required(countryCode, "Country code").toUpperCase(java.util.Locale.ROOT);
		type = required(type, "Tax identifier type").toUpperCase(java.util.Locale.ROOT);
		value = required(value, "Tax identifier value");
		if ("PE".equals(countryCode) && "RUC".equals(type) && !value.matches("\\d{11}"))
			throw new IllegalArgumentException("Peru RUC must contain exactly 11 digits");
	}
	private static String required(String value, String label) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
		return value.trim();
	}
}
