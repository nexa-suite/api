package com.nexa.api.tenantmanagement.application.model;

public record WorkspaceMembershipSummary(String id, String workspaceId, String userId, String email,
		String displayName, String role, String status, long version) { }
