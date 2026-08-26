package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception;

/**
 * Deliberately generic failure for missing, mismatched or inactive tenant/workspace membership.
 */
public final class InaccessibleTenantException extends RuntimeException {
	public InaccessibleTenantException() {
		super("The requested tenant workspace is not accessible");
	}
}
