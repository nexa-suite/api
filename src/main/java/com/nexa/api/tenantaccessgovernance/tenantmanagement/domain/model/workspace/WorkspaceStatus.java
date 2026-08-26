package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;

public enum WorkspaceStatus {
	ACTIVE,
	SUSPENDED,
	PENDING_REVIEW;

	public boolean isAccessible() {
		return this == ACTIVE;
	}

	public static WorkspaceStatus from(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Workspace status is required");
		}
		try {
			return value.strip().replace('-', '_').toUpperCase(Locale.ROOT).transform(WorkspaceStatus::valueOf);
		} catch (IllegalArgumentException exception) {
			throw new TenantManagementInvariantViolation("Unknown workspace status");
		}
	}
}
