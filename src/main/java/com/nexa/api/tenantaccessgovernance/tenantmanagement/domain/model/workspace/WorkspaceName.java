package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.text.Normalizer;

public record WorkspaceName(String value) {
	private static final int MAXIMUM_LENGTH = 160;

	public WorkspaceName {
		value = normalize(value);
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Workspace name is required");
		}
		String normalized = Normalizer.normalize(value.strip().replaceAll("\\s+", " "), Normalizer.Form.NFC);
		if (normalized.isEmpty()) {
			throw new TenantManagementInvariantViolation("Workspace name is required");
		}
		if (normalized.length() > MAXIMUM_LENGTH) {
			throw new TenantManagementInvariantViolation("Workspace name exceeds " + MAXIMUM_LENGTH + " characters");
		}
		if (normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new TenantManagementInvariantViolation("Workspace name contains control characters");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
