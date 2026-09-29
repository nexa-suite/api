package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi;

public final class AccessPolicyViolation extends RuntimeException {
	public AccessPolicyViolation(String message) {
		super(message);
	}
}
