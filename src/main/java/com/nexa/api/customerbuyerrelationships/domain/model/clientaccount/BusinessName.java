package com.nexa.api.customerbuyerrelationships.domain.model.clientaccount;

import com.nexa.api.customerbuyerrelationships.contract.CustomerRelationshipInvariantViolation;

public record BusinessName(String value) {
	public BusinessName { value = text(value, "Business name", 160); }
	static String text(String value, String label, int max) {
		if (value == null || value.isBlank() || value.trim().length() > max) throw new CustomerRelationshipInvariantViolation(label + " is invalid");
		return value.trim();
	}
}
