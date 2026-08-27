package com.nexa.api.customerbuyerrelationships.domain.model.clientaccount;

import com.nexa.api.customerbuyerrelationships.contract.CustomerRelationshipInvariantViolation;

public record PhoneNumber(String value) {
	public PhoneNumber {
		if (value == null || value.isBlank() || !value.trim().matches("^[+0-9 ()-]{7,32}$")) throw new CustomerRelationshipInvariantViolation("Phone number is invalid");
		value = value.trim();
	}
}
