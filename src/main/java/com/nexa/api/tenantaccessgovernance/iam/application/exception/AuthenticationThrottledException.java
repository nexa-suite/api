package com.nexa.api.tenantaccessgovernance.iam.application.exception;

public final class AuthenticationThrottledException extends RuntimeException {
	public AuthenticationThrottledException() {
		super("Authentication temporarily unavailable");
	}
}
