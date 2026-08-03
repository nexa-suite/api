package com.nexa.api.tenantmanagement.domain.model.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record RoleDefinitionId(UUID value) {
	public RoleDefinitionId {
		value = UuidIdentitySupport.required(value, "Role definition id");
	}

	public RoleDefinitionId(String value) { this(UuidIdentitySupport.parse(value, "Role definition id")); }

	public static RoleDefinitionId random() { return new RoleDefinitionId(UUID.randomUUID()); }

	public static RoleDefinitionId system(String code) {
		return new RoleDefinitionId(UUID.nameUUIDFromBytes(("nexa:system-role:" + code).getBytes(StandardCharsets.UTF_8)));
	}

	@Override public String toString() { return value.toString(); }
}
