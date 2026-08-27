package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

import java.util.List;

public record WorkspaceDetails(WorkspaceSummary workspace, List<WorkspaceMembershipSummary> memberships) {
	public WorkspaceDetails { memberships = List.copyOf(memberships); }
}
