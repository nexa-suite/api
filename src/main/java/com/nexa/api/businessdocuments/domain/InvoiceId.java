package com.nexa.api.businessdocuments.domain;

import java.util.Locale;

/** Stable identity for an invoice aggregate. */
public record InvoiceId(String value) {
	public InvoiceId {
		value = normalize(value, "Invoice id");
	}

	private static String normalize(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " is required");
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (normalized.length() > 64) {
			throw new IllegalArgumentException(label + " exceeds 64 characters");
		}
		if (!normalized.matches("[A-Z0-9-]+")) {
			throw new IllegalArgumentException(label + " contains invalid characters");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
