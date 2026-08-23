package com.nexa.api.customerrelationships.contract;

import com.nexa.api.customerrelationships.contract.CustomerRelationshipInvariantViolation;

import java.util.Locale;

public record CustomerAccountId(String value) {
	public CustomerAccountId {
		value = normalized(value, "Client account id", 64);
	}

	private static String normalized(String value, String label, int max) {
		if (value == null || value.isBlank()) throw new CustomerRelationshipInvariantViolation(label + " is required");
		String result = value.trim().toUpperCase(Locale.ROOT);
		if (result.length() > max || !result.matches("[A-Z0-9-]+")) throw new CustomerRelationshipInvariantViolation(label + " is invalid");
		return result;
	}

	@Override public String toString() { return value; }
}
