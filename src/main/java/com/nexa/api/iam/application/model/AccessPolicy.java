package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Policy resolved for a user and client surface. Role vocabulary remains owned by the policy provider.
 */
public record AccessPolicy(ClientSurface surface, String role, Set<String> permissions,
		String tenantId, String tenantSlug, String workspaceId, String workspaceSlug, String membershipId,
		String displayName, String preferredLanguage) {
	public AccessPolicy(ClientSurface surface, String role, Set<String> permissions) {
		this(surface, role, permissions, null, null, null, null, null, null, null);
	}

	public AccessPolicy {
		Objects.requireNonNull(surface, "Client surface is required");
		if (role == null || role.isBlank()) throw new IllegalArgumentException("Role is required");
		role = role.trim();
		if (permissions == null) throw new IllegalArgumentException("Permissions are required");
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String permission : permissions) {
			if (permission == null || permission.isBlank()) throw new IllegalArgumentException("Permission is required");
			normalized.add(permission.trim());
		}
		permissions = Set.copyOf(normalized);
	}

	public boolean allows(String permission) {
		return permission != null && (permissions.contains("*") || permissions.contains(permission));
	}

	public Set<String> roles() {
		return role == null || role.isBlank() ? Set.of() : Set.of(role.split(","));
	}
}
