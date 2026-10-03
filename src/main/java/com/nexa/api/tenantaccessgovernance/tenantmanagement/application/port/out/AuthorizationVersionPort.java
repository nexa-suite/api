package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;

/** Invalidates authorization snapshots after role-definition changes. */
public interface AuthorizationVersionPort {
	void bump(TenantId tenantId, WorkspaceId workspaceId);

	/** Invalidates one membership after a grant changes its effective object authority. */
	default void bump(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId) {
		throw new UnsupportedOperationException("Per-membership authorization version updates are not integrated");
	}
}
