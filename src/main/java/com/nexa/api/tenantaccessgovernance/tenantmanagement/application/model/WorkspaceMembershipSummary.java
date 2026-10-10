package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

public record WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
		String displayName, String membershipType, String status, long version, java.util.Set<String> roles,
		java.util.Set<String> roleDefinitionIds, java.util.Set<String> permissionCodes) {
	public WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
			String displayName, String status, long version, java.util.Set<String> roles,
			java.util.Set<String> roleDefinitionIds, java.util.Set<String> permissionCodes) {
		this(id, workspaceId, userId, email, displayName, "INTERNAL", status, version, roles,
				roleDefinitionIds, permissionCodes);
	}

	public WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
			String displayName, String status, long version, java.util.Set<String> roles) {
		this(id, workspaceId, userId, email, displayName, "INTERNAL", status, version, roles,
				java.util.Set.of(), java.util.Set.of());
	}

	public WorkspaceMembershipSummary {
		if (membershipType == null || membershipType.isBlank()) throw new IllegalArgumentException("Membership type is required");
		roles = roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles);
		roleDefinitionIds = roleDefinitionIds == null ? java.util.Set.of() : java.util.Set.copyOf(roleDefinitionIds);
		permissionCodes = permissionCodes == null ? java.util.Set.of() : java.util.Set.copyOf(permissionCodes);
	}
}
