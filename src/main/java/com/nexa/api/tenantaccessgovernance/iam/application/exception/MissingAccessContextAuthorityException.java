package com.nexa.api.tenantaccessgovernance.iam.application.exception;

public final class MissingAccessContextAuthorityException extends RuntimeException {
	public MissingAccessContextAuthorityException() { super("Access context authority is required"); }
}
