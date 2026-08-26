package com.nexa.api.tenantaccessgovernance.iam.application.exception;

public final class SessionNotFoundException extends RuntimeException {
	public SessionNotFoundException() {
		super("Active session not found");
	}
}
