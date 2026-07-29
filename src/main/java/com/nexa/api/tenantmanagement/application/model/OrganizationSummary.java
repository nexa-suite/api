package com.nexa.api.tenantmanagement.application.model;

public record OrganizationSummary(String id, String name, String slug, String status, String currentWorkspaceId,
		String currentWorkspaceName, long version) { }
