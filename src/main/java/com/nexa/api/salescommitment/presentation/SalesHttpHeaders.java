package com.nexa.api.salescommitment.presentation;

import com.nexa.api.salescommitment.application.exception.SalesPreconditionRequiredException;

public final class SalesHttpHeaders {
	private SalesHttpHeaders() { }
	public static long requireVersion(String value) {
		if (value == null || !value.trim().matches("\\\"?\\d+\\\"?")) throw new SalesPreconditionRequiredException();
		try { return Long.parseLong(value.replace("\"", "").trim()); }
		catch (NumberFormatException exception) { throw new SalesPreconditionRequiredException(); }
	}
	public static String etag(long version) { return "\"" + version + "\""; }
}
