package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.util.Locale;

public record PurchaseRequestId(String value) {
	public PurchaseRequestId { value = normalized(value, "Purchase request id", 64); }
	private static String normalized(String value, String label, int max) {
		if (value == null || value.isBlank()) throw new SalesInvariantViolation(label + " is required");
		String result = value.trim().toUpperCase(Locale.ROOT);
		if (result.length() > max || !result.matches("[A-Z0-9-]+")) throw new SalesInvariantViolation(label + " is invalid");
		return result;
	}
	@Override public String toString() { return value; }
}
