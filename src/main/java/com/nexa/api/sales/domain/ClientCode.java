package com.nexa.api.sales.domain;

public record ClientCode(String value) {
	public ClientCode { value = normalized(value, "Client code"); }
	private static String normalized(String value, String label) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
		String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
		if (!normalized.matches("[A-Z0-9-]{2,32}")) throw new IllegalArgumentException(label + " is invalid");
		return normalized;
	}
}
