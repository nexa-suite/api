package com.nexa.api.tenantaccessgovernance.iam.application.model;

/** A currently eligible selection option. Its identifier is intent and is revalidated on selection. */
public record AccessContextOption(String membershipId, String tenantId, String tenantName, String tenantSlug,
		String workspaceId, String workspaceName, String workspaceSlug) { }
