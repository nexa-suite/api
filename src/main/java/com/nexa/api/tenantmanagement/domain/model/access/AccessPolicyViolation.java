package com.nexa.api.tenantmanagement.domain.model.access;

public final class AccessPolicyViolation extends RuntimeException {
	public AccessPolicyViolation(String message) {
		super(message);
	}
}
