package com.nexa.api.invoicing.domain;

import java.util.Locale;

/** Human-facing invoice number, kept distinct from the internal invoice id. */
public record InvoiceNumber(String value) {
	public InvoiceNumber {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Invoice number is required");
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (normalized.length() > 64) {
			throw new IllegalArgumentException("Invoice number exceeds 64 characters");
		}
		if (!normalized.matches("[A-Z0-9-]+")) {
			throw new IllegalArgumentException("Invoice number contains invalid characters");
		}
		value = normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
