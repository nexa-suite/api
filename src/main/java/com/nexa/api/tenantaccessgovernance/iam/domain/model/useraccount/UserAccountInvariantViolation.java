package com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount;

public final class UserAccountInvariantViolation extends IllegalArgumentException {
	public UserAccountInvariantViolation(String message) {
		super(message);
	}
}
