package com.nexa.api.salescommitment.application.exception;

public final class SalesResourceNotFoundException extends RuntimeException {
	public SalesResourceNotFoundException(String resource) { super(resource); }
}
