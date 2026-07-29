package com.nexa.api.tenantmanagement.domain.model.identity;

import java.util.UUID;

public record TenantId(UUID value) {
	public TenantId {
		value = UuidIdentitySupport.required(value, "Tenant id");
	}

	public TenantId(String value) {
		this(UuidIdentitySupport.parse(value, "Tenant id"));
	}

	public static TenantId random() {
		return new TenantId(UUID.randomUUID());
	}

	public static TenantId from(String value) {
		return new TenantId(value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
