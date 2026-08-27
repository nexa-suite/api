package com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount;

public record UserAccountId(String value) {
	public UserAccountId {
		value = normalize(value, "User account id");
	}

	private static String normalize(String value, String label) {
		if (value == null || value.isBlank()) throw new UserAccountInvariantViolation(label + " is required");
		String normalized = value.trim();
		if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
			throw new UserAccountInvariantViolation(label + " contains invalid characters");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
