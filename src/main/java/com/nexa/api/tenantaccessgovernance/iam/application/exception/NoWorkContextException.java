package com.nexa.api.tenantaccessgovernance.iam.application.exception;

public final class NoWorkContextException extends RuntimeException {
	public NoWorkContextException() { super("No eligible work context is available"); }
}
