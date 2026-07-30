package com.nexa.api.sales.domain.model.salesorder;

public final class SalesOrderInvariantViolation extends RuntimeException {
	public SalesOrderInvariantViolation(String message) { super(message); }
}
