package com.nexa.api.iam.application.model;

import java.util.Locale;

public record LoginIdentifier(String value) {
	public LoginIdentifier {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Login identifier is required");
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.length() > 254) throw new IllegalArgumentException("Login identifier exceeds 254 characters");
		value = normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
