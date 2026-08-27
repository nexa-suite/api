package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model;

/**
 * Raised when a tenant-management value or relationship violates a domain invariant.
 */
public final class TenantManagementInvariantViolation extends IllegalArgumentException {
	public TenantManagementInvariantViolation(String message) {
		super(message);
	}
}
