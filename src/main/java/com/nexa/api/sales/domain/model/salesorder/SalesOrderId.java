package com.nexa.api.sales.domain.model.salesorder;

import java.util.Locale;

public record SalesOrderId(String value) {
	public SalesOrderId { value = normalize(value, "Sales order id"); }
	private static String normalize(String value, String label) { if (value == null || value.isBlank()) throw new SalesOrderInvariantViolation(label + " is required"); String normalized=value.trim().toUpperCase(Locale.ROOT); if (!normalized.matches("[A-Z0-9-]{2,64}")) throw new SalesOrderInvariantViolation(label + " is invalid"); return normalized; }
	@Override public String toString() { return value; }
}
