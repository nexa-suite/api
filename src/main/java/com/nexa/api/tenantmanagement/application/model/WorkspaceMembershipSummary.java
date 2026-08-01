package com.nexa.api.tenantmanagement.application.model;

public record WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
		String displayName, String role, String status, long version, java.util.Set<String> roles) {
	public WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
			String displayName, String role, String status, long version) {
		this(id, workspaceId, userId, email, displayName, role, status, version,
			role == null || role.isBlank() ? java.util.Set.of() : java.util.Set.of(role));
	}

	public WorkspaceMembershipSummary {
		roles = roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles);
		if (role == null && !roles.isEmpty()) role = roles.iterator().next();
	}
}
