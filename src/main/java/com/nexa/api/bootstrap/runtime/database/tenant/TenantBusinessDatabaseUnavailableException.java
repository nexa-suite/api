package com.nexa.api.bootstrap.runtime.database.tenant;

public final class TenantBusinessDatabaseUnavailableException extends RuntimeException {
	public TenantBusinessDatabaseUnavailableException(String message) {
		super(message);
	}

	public TenantBusinessDatabaseUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
