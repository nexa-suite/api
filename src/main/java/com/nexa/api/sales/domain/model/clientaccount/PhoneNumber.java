package com.nexa.api.sales.domain.model.clientaccount;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record PhoneNumber(String value) {
	public PhoneNumber {
		if (value == null || value.isBlank() || !value.trim().matches("^[+0-9 ()-]{7,32}$")) throw new SalesInvariantViolation("Phone number is invalid");
		value = value.trim();
	}
}
