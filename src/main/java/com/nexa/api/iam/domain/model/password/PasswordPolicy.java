package com.nexa.api.iam.domain.model.password;

public final class PasswordPolicy {
	public static final int MINIMUM_LENGTH = 12;
	public static final int MAXIMUM_LENGTH = 128;

	private PasswordPolicy() {}

	public static boolean isValid(String password) {
		return password != null && password.length() >= MINIMUM_LENGTH && password.length() <= MAXIMUM_LENGTH
				&& password.chars().noneMatch(Character::isISOControl);
	}
}
