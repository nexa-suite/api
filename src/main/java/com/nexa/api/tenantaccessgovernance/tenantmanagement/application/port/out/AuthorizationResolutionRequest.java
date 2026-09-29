package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;

import java.util.Set;

/** Persistence-neutral reference used before dynamic assignments are materialized. */
public record AuthorizationResolutionRequest(MembershipId membershipId, UserId userId, TenantId tenantId,
		WorkspaceId workspaceId, String membershipType, Set<MembershipRole> fixedRoles, long authorizationVersion) {
	public AuthorizationResolutionRequest {
		membershipId = java.util.Objects.requireNonNull(membershipId, "Membership id is required");
		userId = java.util.Objects.requireNonNull(userId, "User id is required");
		tenantId = java.util.Objects.requireNonNull(tenantId, "Tenant id is required");
		workspaceId = java.util.Objects.requireNonNull(workspaceId, "Workspace id is required");
		membershipType = membershipType == null ? "INTERNAL" : membershipType.trim();
		fixedRoles = fixedRoles == null ? Set.of() : Set.copyOf(fixedRoles);
		if (authorizationVersion < 0) throw new IllegalArgumentException("Authorization version cannot be negative");
	}
}
