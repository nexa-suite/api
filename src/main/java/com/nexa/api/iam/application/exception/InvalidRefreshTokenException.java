package com.nexa.api.iam.application.exception;

public class InvalidRefreshTokenException extends RuntimeException {
	public InvalidRefreshTokenException() {
		super("Invalid refresh token");
	}
}
