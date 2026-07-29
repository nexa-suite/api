package com.nexa.api.tenantmanagement.domain.model.tenant;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;

import java.util.Objects;

public record Tenant(TenantId id, TenantName name, TenantSlug slug, TenantStatus status) {
	public Tenant {
		id = Objects.requireNonNull(id, "Tenant id is required");
		name = Objects.requireNonNull(name, "Tenant name is required");
		slug = Objects.requireNonNull(slug, "Tenant slug is required");
		status = Objects.requireNonNull(status, "Tenant status is required");
		if (slug.value().isBlank()) {
			throw new TenantManagementInvariantViolation("Tenant slug is required");
		}
	}

	public boolean isAccessible() {
		return status.isAccessible();
	}
}
