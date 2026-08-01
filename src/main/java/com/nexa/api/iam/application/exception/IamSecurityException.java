package com.nexa.api.iam.application.exception;

public final class IamSecurityException extends RuntimeException {
	private final String code;

	public IamSecurityException(String code) {
		super(code);
		this.code = code;
	}

	public String code() { return code; }
}
