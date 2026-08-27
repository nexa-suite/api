package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;

import java.util.Objects;

public record Workspace(WorkspaceId id, TenantId tenantId, WorkspaceName name, WorkspaceSlug slug,
		WorkspaceStatus status) {
	public Workspace {
		id = Objects.requireNonNull(id, "Workspace id is required");
		tenantId = Objects.requireNonNull(tenantId, "Workspace tenant id is required");
		name = Objects.requireNonNull(name, "Workspace name is required");
		slug = Objects.requireNonNull(slug, "Workspace slug is required");
		status = Objects.requireNonNull(status, "Workspace status is required");
	}

	public boolean isAccessible() {
		return status.isAccessible();
	}
}
