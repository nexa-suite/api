package com.nexa.api.bootstrap.runtime.database.tenant;

public final class TenantBusinessDatabaseUnavailableException extends com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException {
	public TenantBusinessDatabaseUnavailableException(String message) {
		super(message);
	}

	public TenantBusinessDatabaseUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
