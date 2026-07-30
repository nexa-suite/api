package com.nexa.api.iam.application.exception;

public final class SessionNotFoundException extends RuntimeException {
	public SessionNotFoundException() {
		super("Active session not found");
	}
}
