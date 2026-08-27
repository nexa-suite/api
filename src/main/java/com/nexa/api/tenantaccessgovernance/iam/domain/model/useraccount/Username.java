package com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount;

import java.util.Locale;

public record Username(String value) {
	public Username {
		if (value == null || value.isBlank()) throw new UserAccountInvariantViolation("Username is required");
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.length() > 120 || !normalized.matches("[a-z0-9][a-z0-9._@+-]*")) {
			throw new UserAccountInvariantViolation("Username contains invalid characters");
		}
		value = normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
