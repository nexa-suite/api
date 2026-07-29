package com.nexa.api.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.util.Objects;

public record Membership(MembershipId id, UserId userId, TenantId tenantId, WorkspaceId workspaceId,
		MembershipRole role, MembershipStatus status) {
	public Membership {
		id = Objects.requireNonNull(id, "Membership id is required");
		userId = Objects.requireNonNull(userId, "Membership user id is required");
		tenantId = Objects.requireNonNull(tenantId, "Membership tenant id is required");
		workspaceId = Objects.requireNonNull(workspaceId, "Membership workspace id is required");
		role = Objects.requireNonNull(role, "Membership role is required");
		status = Objects.requireNonNull(status, "Membership status is required");
	}

	public boolean isActive() {
		return status.isActive();
	}

	public boolean belongsTo(UserId requestedUserId, TenantId requestedTenantId, WorkspaceId requestedWorkspaceId) {
		return userId.equals(requestedUserId)
				&& tenantId.equals(requestedTenantId)
				&& workspaceId.equals(requestedWorkspaceId);
	}
}
