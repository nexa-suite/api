package com.nexa.api.tenantmanagement.domain.model.identity;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.UUID;

final class UuidIdentitySupport {
	private UuidIdentitySupport() {
	}

	static UUID required(UUID value, String label) {
		if (value == null) {
			throw new TenantManagementInvariantViolation(label + " is required");
		}
		return value;
	}

	static UUID parse(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation(label + " is required");
		}
		String normalized = value.strip();
		try {
			UUID parsed = UUID.fromString(normalized);
			if (!parsed.toString().equalsIgnoreCase(normalized)) {
				throw new IllegalArgumentException("non-canonical UUID");
			}
			return parsed;
		} catch (IllegalArgumentException exception) {
			throw new TenantManagementInvariantViolation(label + " must be a canonical UUID");
		}
	}
}
