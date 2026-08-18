package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Policy resolved for a user and client surface. Role vocabulary remains owned by the policy provider.
 */
public record AccessPolicy(ClientSurface surface, Set<String> roles, Set<String> permissions,
		String tenantId, String tenantSlug, String workspaceId, String workspaceSlug, String membershipId,
		String displayName, String preferredLanguage, long authorizationVersion, Set<String> roleDefinitionIds) {
	public AccessPolicy(ClientSurface surface, Set<String> roles, Set<String> permissions,
			String tenantId, String tenantSlug, String workspaceId, String workspaceSlug, String membershipId,
			String displayName, String preferredLanguage) {
		this(surface, roles, permissions, tenantId, tenantSlug, workspaceId, workspaceSlug, membershipId,
				displayName, preferredLanguage, 0, Set.of());
	}

	public AccessPolicy(ClientSurface surface, Set<String> roles, Set<String> permissions,
			String tenantId, String tenantSlug, String workspaceId, String workspaceSlug, String membershipId,
			String displayName, String preferredLanguage, long authorizationVersion) {
		this(surface, roles, permissions, tenantId, tenantSlug, workspaceId, workspaceSlug, membershipId,
				displayName, preferredLanguage, authorizationVersion, Set.of());
	}

	public AccessPolicy {
		Objects.requireNonNull(surface, "Client surface is required");
		if (roles == null || roles.isEmpty() || roles.stream().anyMatch(value -> value == null || value.isBlank())) throw new IllegalArgumentException("Roles are required");
		roles = roles.stream().map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (permissions == null) throw new IllegalArgumentException("Permissions are required");
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String permission : permissions) {
			if (permission == null || permission.isBlank()) throw new IllegalArgumentException("Permission is required");
			normalized.add(permission.trim());
		}
		permissions = Set.copyOf(normalized);
		if (roleDefinitionIds == null) throw new IllegalArgumentException("Role definition ids are required");
		roleDefinitionIds = roleDefinitionIds.stream().map(value -> {
			if (value == null || value.isBlank()) throw new IllegalArgumentException("Role definition id is required");
			return value.trim();
		}).collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (authorizationVersion < 0) throw new IllegalArgumentException("Authorization version cannot be negative");
	}

	public boolean allows(String permission) {
		return permission != null && (permissions.contains("*") || permissions.contains(permission));
	}

}
