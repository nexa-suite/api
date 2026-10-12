package com.nexa.api.bootstrap.runtime.database.tenant;

/** Safe readiness failure when the dedicated local policy-snapshot writer is not configured. */
public final class TenantBusinessDatabasePolicySnapshotWriterUnavailableException extends RuntimeException {
	public TenantBusinessDatabasePolicySnapshotWriterUnavailableException() {
		super("Dedicated Tenant policy-snapshot writer credentials are unavailable");
	}

	public TenantBusinessDatabasePolicySnapshotWriterUnavailableException(Throwable cause) {
		super("Dedicated Tenant policy-snapshot writer is unavailable", cause);
	}
}
