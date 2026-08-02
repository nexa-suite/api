package com.nexa.api.tenantmanagement.application.model;

public record WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
		String displayName, String status, long version, java.util.Set<String> roles) {
	public WorkspaceMembershipSummary {
		roles = roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles);
	}
}
