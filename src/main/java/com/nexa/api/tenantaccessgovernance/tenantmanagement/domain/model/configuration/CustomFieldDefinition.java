package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public record CustomFieldDefinition(UUID id, String fieldKey, String label, String fieldKind, String scope,
		boolean required, boolean uniqueValue, int displayOrder, boolean active, long version) {
	public CustomFieldDefinition {
		if (id == null) throw new TenantManagementInvariantViolation("Custom field id is required");
		fieldKey = required(fieldKey, "Field key").toLowerCase(Locale.ROOT);
		label = required(label, "Field label");
		fieldKind = required(fieldKind, "Field kind").toUpperCase(Locale.ROOT);
		scope = required(scope, "Field scope").toUpperCase(Locale.ROOT);
		if (!fieldKey.matches("[a-z][a-z0-9_-]{2,63}")) throw new TenantManagementInvariantViolation("Field key is invalid");
		if (!Set.of("TEXT", "NUMBER", "DECIMAL", "BOOLEAN", "DATE").contains(fieldKind)) throw new TenantManagementInvariantViolation("Field kind is invalid");
		if (!Set.of("PRODUCT", "CLIENT_ACCOUNT", "DISPATCH", "ORDER").contains(scope)) throw new TenantManagementInvariantViolation("Field scope is invalid");
		if (displayOrder < 0 || displayOrder > 10000) throw new TenantManagementInvariantViolation("Field order is invalid");
	}

	private static String required(String value, String name) {
		String normalized = value == null ? "" : value.strip();
		if (normalized.isBlank() || normalized.length() > 160) throw new TenantManagementInvariantViolation(name + " is invalid");
		return normalized;
	}
}
