package com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount;

public record DisplayName(String value) {
	public DisplayName {
		if (value == null || value.isBlank()) throw new UserAccountInvariantViolation("Display name is required");
		String normalized = value.trim();
		if (normalized.length() > 160) throw new UserAccountInvariantViolation("Display name exceeds 160 characters");
		value = normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
