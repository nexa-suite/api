package com.nexa.api.tenantaccessgovernance.iam.application.exception;

public final class InvalidCredentialsException extends RuntimeException {
	public InvalidCredentialsException() {
		super("Invalid credentials");
	}
}
