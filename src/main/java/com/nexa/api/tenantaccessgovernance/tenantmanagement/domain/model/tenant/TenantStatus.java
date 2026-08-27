package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;

public enum TenantStatus {
	ACTIVE,
	SUSPENDED,
	PENDING_REVIEW;

	public boolean isAccessible() {
		return this == ACTIVE;
	}

	public static TenantStatus from(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Tenant status is required");
		}
		try {
			return value.strip().replace('-', '_').toUpperCase(Locale.ROOT).transform(TenantStatus::valueOf);
		} catch (IllegalArgumentException exception) {
			throw new TenantManagementInvariantViolation("Unknown tenant status");
		}
	}
}
