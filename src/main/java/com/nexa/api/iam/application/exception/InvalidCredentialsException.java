package com.nexa.api.iam.application.exception;

public final class InvalidCredentialsException extends RuntimeException {
	public InvalidCredentialsException() {
		super("Invalid credentials");
	}
}
