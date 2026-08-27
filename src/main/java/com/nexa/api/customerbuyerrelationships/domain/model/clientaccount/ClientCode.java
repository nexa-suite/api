package com.nexa.api.customerbuyerrelationships.domain.model.clientaccount;

import com.nexa.api.customerbuyerrelationships.contract.CustomerRelationshipInvariantViolation;

import java.util.Locale;

public record ClientCode(String value) {
	public ClientCode {
		if (value == null || value.isBlank()) throw new CustomerRelationshipInvariantViolation("Client code is required");
		value = value.trim().toUpperCase(Locale.ROOT);
		if (!value.matches("[A-Z0-9-]{2,32}")) throw new CustomerRelationshipInvariantViolation("Client code is invalid");
	}
}
