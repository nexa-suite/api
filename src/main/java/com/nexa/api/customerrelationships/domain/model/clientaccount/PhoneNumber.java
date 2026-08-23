package com.nexa.api.customerrelationships.domain.model.clientaccount;

import com.nexa.api.customerrelationships.contract.CustomerRelationshipInvariantViolation;

public record PhoneNumber(String value) {
	public PhoneNumber {
		if (value == null || value.isBlank() || !value.trim().matches("^[+0-9 ()-]{7,32}$")) throw new CustomerRelationshipInvariantViolation("Phone number is invalid");
		value = value.trim();
	}
}
