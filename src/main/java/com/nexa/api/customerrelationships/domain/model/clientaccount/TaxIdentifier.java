package com.nexa.api.customerrelationships.domain.model.clientaccount;

import com.nexa.api.customerrelationships.contract.CustomerRelationshipInvariantViolation;

public record TaxIdentifier(String countryCode, String type, String value) {
	public TaxIdentifier {
		countryCode = required(countryCode, "Country code").toUpperCase(java.util.Locale.ROOT);
		type = required(type, "Tax identifier type").toUpperCase(java.util.Locale.ROOT);
		value = required(value, "Tax identifier value");
		if ("PE".equals(countryCode) && "RUC".equals(type) && !value.matches("\\d{11}")) throw new CustomerRelationshipInvariantViolation("Peru RUC must contain exactly 11 digits");
	}
	private static String required(String value, String label) {
		if (value == null || value.isBlank()) throw new CustomerRelationshipInvariantViolation(label + " is required");
		return value.trim();
	}
}
