package com.nexa.api.sales.domain.model.clientaccount;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.util.Locale;

public record ClientCode(String value) {
	public ClientCode {
		if (value == null || value.isBlank()) throw new SalesInvariantViolation("Client code is required");
		value = value.trim().toUpperCase(Locale.ROOT);
		if (!value.matches("[A-Z0-9-]{2,32}")) throw new SalesInvariantViolation("Client code is invalid");
	}
}
