package com.nexa.api.shared.application.error;

/** Technical failure raised when a verified Tenant database cannot serve a request. */
public class TenantBusinessDatabaseUnavailableException extends RuntimeException {
	public TenantBusinessDatabaseUnavailableException(String message) {
		super(message);
	}

	public TenantBusinessDatabaseUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
