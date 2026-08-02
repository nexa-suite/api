package com.nexa.api.iam.domain.model.password;

/** Compatibility alias retaining the IAM-facing package for existing callers and tests. */
public final class PasswordPolicy {
	public static final int MINIMUM_LENGTH = com.nexa.api.shared.domain.model.password.PasswordPolicy.MINIMUM_LENGTH;
	public static final int MAXIMUM_LENGTH = com.nexa.api.shared.domain.model.password.PasswordPolicy.MAXIMUM_LENGTH;

	private PasswordPolicy() { }

	public static boolean isValid(String password) {
		return com.nexa.api.shared.domain.model.password.PasswordPolicy.isValid(password);
	}
}
