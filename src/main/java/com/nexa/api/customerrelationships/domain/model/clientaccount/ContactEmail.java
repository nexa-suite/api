package com.nexa.api.customerrelationships.domain.model.clientaccount;

import com.nexa.api.customerrelationships.contract.CustomerRelationshipInvariantViolation;

public record ContactEmail(String value) {
	public ContactEmail {
		if (value == null || value.isBlank() || !value.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new CustomerRelationshipInvariantViolation("Contact email is invalid");
		value = value.trim().toLowerCase(java.util.Locale.ROOT);
	}
}
