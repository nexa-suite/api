package com.nexa.api.bootstrap.runtime.database.tenant;

/** Safe readiness failure when central READY evidence does not match this application's Tenant schema manifest. */
public final class TenantBusinessDatabaseSchemaManifestMismatchException extends RuntimeException {
	public TenantBusinessDatabaseSchemaManifestMismatchException() {
		super("Tenant schema manifest evidence is missing or stale");
	}
}
