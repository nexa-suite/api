package com.nexa.api.iam.domain.model.useraccount;

public final class UserAccountInvariantViolation extends IllegalArgumentException {
	public UserAccountInvariantViolation(String message) {
		super(message);
	}
}
