package com.nexa.api.sales.domain.model.clientaccount;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record BusinessName(String value) {
	public BusinessName { value = text(value, "Business name", 160); }
	static String text(String value, String label, int max) {
		if (value == null || value.isBlank() || value.trim().length() > max) throw new SalesInvariantViolation(label + " is invalid");
		return value.trim();
	}
}
