package com.nexa.api.salescommitment.domain.publicapi;

public final class SalesInvariantViolation extends RuntimeException {
	public SalesInvariantViolation(String message) {
		super(message);
	}
}
