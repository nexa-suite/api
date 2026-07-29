package com.nexa.api.sales.domain.model.salesorder;

public record SalesOrderNumber(String value) {
	public SalesOrderNumber { if (value == null || !value.trim().toUpperCase(java.util.Locale.ROOT).matches("SO-[A-Z0-9-]{2,32}")) throw new SalesOrderInvariantViolation("Sales order number is invalid"); value=value.trim().toUpperCase(java.util.Locale.ROOT); }
}
