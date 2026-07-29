package com.nexa.api.iam.domain.model.session;

public final class SessionInvariantViolation extends IllegalArgumentException {
	public SessionInvariantViolation(String message) {
		super(message);
	}
}
