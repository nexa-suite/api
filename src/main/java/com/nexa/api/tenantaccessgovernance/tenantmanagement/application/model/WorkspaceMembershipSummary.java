package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

public record WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
		String displayName, String status, long version, java.util.Set<String> roles,
		java.util.Set<String> roleDefinitionIds, java.util.Set<String> permissionCodes) {
	public WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
			String displayName, String status, long version, java.util.Set<String> roles) {
		this(id, workspaceId, userId, email, displayName, status, version, roles, java.util.Set.of(), java.util.Set.of());
	}

	public WorkspaceMembershipSummary {
		roles = roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles);
		roleDefinitionIds = roleDefinitionIds == null ? java.util.Set.of() : java.util.Set.copyOf(roleDefinitionIds);
		permissionCodes = permissionCodes == null ? java.util.Set.of() : java.util.Set.copyOf(permissionCodes);
	}
}
