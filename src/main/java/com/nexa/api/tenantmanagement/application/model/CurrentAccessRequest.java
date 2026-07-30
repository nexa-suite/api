package com.nexa.api.tenantmanagement.application.model;

import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.util.Objects;

public record CurrentAccessRequest(UserId userId, TenantId tenantId, WorkspaceId workspaceId, Surface surface) {
	public CurrentAccessRequest {
		userId = Objects.requireNonNull(userId, "User id is required");
		tenantId = Objects.requireNonNull(tenantId, "Tenant id is required");
		workspaceId = Objects.requireNonNull(workspaceId, "Workspace id is required");
		surface = Objects.requireNonNull(surface, "Surface is required");
	}
}
