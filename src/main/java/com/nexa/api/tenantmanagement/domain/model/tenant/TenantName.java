package com.nexa.api.tenantmanagement.domain.model.tenant;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.text.Normalizer;

public record TenantName(String value) {
	private static final int MAXIMUM_LENGTH = 160;

	public TenantName {
		value = normalize(value);
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Tenant name is required");
		}
		String normalized = Normalizer.normalize(value.strip().replaceAll("\\s+", " "), Normalizer.Form.NFC);
		if (normalized.isEmpty()) {
			throw new TenantManagementInvariantViolation("Tenant name is required");
		}
		if (normalized.length() > MAXIMUM_LENGTH) {
			throw new TenantManagementInvariantViolation("Tenant name exceeds " + MAXIMUM_LENGTH + " characters");
		}
		if (normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new TenantManagementInvariantViolation("Tenant name contains control characters");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
