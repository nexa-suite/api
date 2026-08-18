package com.nexa.api.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;

import java.util.Objects;

/**
 * Immutable result of membership verification, including the statuses needed for access decisions.
 */
public record VerifiedMembership(Membership membership, TenantStatus tenantStatus, WorkspaceStatus workspaceStatus,
		EffectiveAuthorization authorization) {
	public VerifiedMembership(Membership membership, TenantStatus tenantStatus, WorkspaceStatus workspaceStatus) {
		this(membership, tenantStatus, workspaceStatus,
				EffectiveAuthorization.fixed(membership.roles(), membership.authorizationVersion()));
	}

	public VerifiedMembership {
		membership = Objects.requireNonNull(membership, "Membership is required");
		tenantStatus = Objects.requireNonNull(tenantStatus, "Tenant status is required");
		workspaceStatus = Objects.requireNonNull(workspaceStatus, "Workspace status is required");
		authorization = Objects.requireNonNull(authorization, "Effective authorization is required");
		if (authorization.authorizationVersion() != membership.authorizationVersion()) {
			throw new IllegalArgumentException("Authorization version does not match membership version");
		}
	}

	public boolean belongsTo(UserId userId, TenantId tenantId, WorkspaceId workspaceId) {
		return membership.belongsTo(userId, tenantId, workspaceId);
	}

	public boolean isAccessible() {
		return membership.isActive() && tenantStatus.isAccessible() && workspaceStatus.isAccessible();
	}
}
