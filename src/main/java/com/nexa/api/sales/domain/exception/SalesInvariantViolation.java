package com.nexa.api.sales.domain.exception;

public final class SalesInvariantViolation extends RuntimeException {
	public SalesInvariantViolation(String message) {
		super(message);
	}
}
