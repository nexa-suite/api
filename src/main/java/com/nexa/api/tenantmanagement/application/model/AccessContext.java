package com.nexa.api.tenantmanagement.application.model;

import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.util.Set;

public interface AccessContext {
	UserId userId();

	TenantId tenantId();

	WorkspaceId workspaceId();

	MembershipId membershipId();

	Surface surface();

	Set<Permission> permissions();

	boolean allows(Permission permission);

	void requireAccess(TenantId tenantId, WorkspaceId workspaceId, Surface surface, Permission permission);
}
