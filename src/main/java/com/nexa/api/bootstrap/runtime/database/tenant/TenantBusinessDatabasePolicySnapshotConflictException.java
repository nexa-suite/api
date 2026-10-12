package com.nexa.api.bootstrap.runtime.database.tenant;

/** Safe failure for a missing, stale or concurrently replaced Tenant policy snapshot. */
public final class TenantBusinessDatabasePolicySnapshotConflictException extends RuntimeException {
	public TenantBusinessDatabasePolicySnapshotConflictException() {
		super("Tenant purchase-request expiry policy snapshot is missing, stale or concurrently changed");
	}
}
