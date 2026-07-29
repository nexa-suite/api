package com.nexa.api.sales.application.exception;

public final class SalesResourceNotFoundException extends RuntimeException {
	public SalesResourceNotFoundException(String resource) { super(resource); }
}
