package com.nexa.api.tenantmanagement.application.port.out;

import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.VerifiedMembership;

import java.util.Optional;

/**
 * Resolves membership and current tenant/workspace statuses from an authoritative adapter.
 * Implementations must scope the lookup by all three identities and revalidate active membership.
 */
public interface VerifiedMembershipResolutionPort {
	Optional<VerifiedMembership> resolve(UserId userId, TenantId tenantId, WorkspaceId workspaceId);

	default Optional<VerifiedMembership> findVerifiedMembership(UserId userId, TenantId tenantId,
			WorkspaceId workspaceId) {
		return resolve(userId, tenantId, workspaceId);
	}
}
