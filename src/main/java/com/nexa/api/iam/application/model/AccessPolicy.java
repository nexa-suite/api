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
		String displayName, String preferredLanguage) {
	public AccessPolicy(ClientSurface surface, String role, Set<String> permissions) {
		this(surface, splitRoles(role), permissions, null, null, null, null, null, null, null);
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
	}

	public boolean allows(String permission) {
		return permission != null && (permissions.contains("*") || permissions.contains(permission));
	}

	/** Compatibility response only. Security decisions use canonical roles(). */
	@Deprecated
	public String role() { return roles.stream().sorted(java.util.Comparator.comparingInt(AccessPolicy::rolePriority)).findFirst().orElseThrow(); }

	private static Set<String> splitRoles(String value) {
		if (value == null || value.isBlank()) return Set.of();
		return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}
	private static int rolePriority(String role) {
		return switch (role) { case "TENANT_ADMIN" -> 0; case "COMPANY_OWNER" -> 1; case "SALES" -> 2; case "WAREHOUSE" -> 3; case "LOGISTICS" -> 4; case "BUYER" -> 5; default -> 99; };
	}
}
