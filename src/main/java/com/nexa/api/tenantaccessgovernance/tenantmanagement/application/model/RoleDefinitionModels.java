package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinitionStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinitionType;

import java.time.Instant;
import java.util.Set;

public final class RoleDefinitionModels {
	private RoleDefinitionModels() { }

	public record CreateCommand(String workspaceId, String code, String name, String description,
			Set<String> permissions) { }

	public record UpdateCommand(String name, String description, Set<String> permissions) { }

	public record View(String id, String tenantId, String workspaceId, RoleDefinitionType type,
			String code, String name, String description, Set<String> permissions,
			RoleDefinitionStatus status, String createdBy, Instant createdAt, Instant updatedAt, long version) {
		public static View from(RoleDefinition value) {
			return new View(value.id().toString(), value.tenantId() == null ? null : value.tenantId().toString(),
				value.workspaceId() == null ? null : value.workspaceId().toString(), value.type(), value.code(), value.name(),
				value.description(), PermissionCatalog.codes(value.permissions()), value.status(),
				value.createdBy() == null ? null : value.createdBy().toString(), value.createdAt(), value.updatedAt(), value.version());
		}
	}

	public record CatalogEntry(String code, String group, Set<String> legacyCodes) {
		public static CatalogEntry from(PermissionKey value) {
			return new CatalogEntry(value.code(), value.group().name(), value.legacyCodes());
		}
	}
}
