package com.nexa.api.iam.application.model;

public record WorkspacePreview(boolean recognized, String displayName, String workspaceUrl, String logoUrl,
		boolean loginAvailable) {
	public static WorkspacePreview unknown() { return new WorkspacePreview(false, null, null, null, false); }
}
