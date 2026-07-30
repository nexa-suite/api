package com.nexa.api.sales.domain.model.clientaccount;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record ContactEmail(String value) {
	public ContactEmail {
		if (value == null || value.isBlank() || !value.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new SalesInvariantViolation("Contact email is invalid");
		value = value.trim().toLowerCase(java.util.Locale.ROOT);
	}
}
