package com.nexa.api.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;

import java.util.Objects;

/**
 * Immutable result of membership verification, including the statuses needed for access decisions.
 */
public record VerifiedMembership(Membership membership, TenantStatus tenantStatus, WorkspaceStatus workspaceStatus) {
	public VerifiedMembership {
		membership = Objects.requireNonNull(membership, "Membership is required");
		tenantStatus = Objects.requireNonNull(tenantStatus, "Tenant status is required");
		workspaceStatus = Objects.requireNonNull(workspaceStatus, "Workspace status is required");
	}

	public boolean belongsTo(UserId userId, TenantId tenantId, WorkspaceId workspaceId) {
		return membership.belongsTo(userId, tenantId, workspaceId);
	}

	public boolean isAccessible() {
		return membership.isActive() && tenantStatus.isAccessible() && workspaceStatus.isAccessible();
	}
}
