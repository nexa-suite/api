package com.nexa.api.iam.application.exception;

public final class AuthenticationThrottledException extends RuntimeException {
	public AuthenticationThrottledException() {
		super("Authentication temporarily unavailable");
	}
}
