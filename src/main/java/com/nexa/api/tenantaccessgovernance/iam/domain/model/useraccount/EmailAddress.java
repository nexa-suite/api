package com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount;

import java.util.Locale;

public record EmailAddress(String value) {
	public EmailAddress {
		if (value == null || value.isBlank()) throw new UserAccountInvariantViolation("Email address is required");
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.length() > 254 || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
			throw new UserAccountInvariantViolation("Email address is invalid");
		}
		value = normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
