package com.nexa.api.tenantmanagement.application.port.out;

import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

/** Invalidates authorization snapshots after role-definition changes. */
public interface AuthorizationVersionPort {
	void bump(TenantId tenantId, WorkspaceId workspaceId);
}
