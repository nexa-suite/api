package com.nexa.api.salescommitment.domain.model.salesorder;

public record SalesOrderNumber(String value) {
	public SalesOrderNumber { if (value == null || !value.trim().toUpperCase(java.util.Locale.ROOT).matches("SO-\\d{4}-\\d{6}")) throw new SalesOrderInvariantViolation("Sales order number is invalid"); value=value.trim().toUpperCase(java.util.Locale.ROOT); }
}
