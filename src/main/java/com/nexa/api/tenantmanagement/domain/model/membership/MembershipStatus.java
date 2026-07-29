package com.nexa.api.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;

public enum MembershipStatus {
	ACTIVE,
	INVITED,
	DISABLED;

	public boolean isActive() {
		return this == ACTIVE;
	}

	public static MembershipStatus from(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Membership status is required");
		}
		try {
			return value.strip().replace('-', '_').toUpperCase(Locale.ROOT).transform(MembershipStatus::valueOf);
		} catch (IllegalArgumentException exception) {
			throw new TenantManagementInvariantViolation("Unknown membership status");
		}
	}
}
