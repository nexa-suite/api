package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;

import java.util.Set;

public interface AccessContext {
	UserId userId();

	TenantId tenantId();

	WorkspaceId workspaceId();

	MembershipId membershipId();

	long authorizationVersion();

	Surface surface();

	Set<Permission> permissions();

	default Set<String> roleCodes() { return java.util.Set.of(); }

	default Set<String> roleDefinitionIds() { return java.util.Set.of(); }

	default Set<String> permissionCodes() { return java.util.Set.of(); }

	boolean allows(Permission permission);

	default boolean allows(PermissionKey permission) { return permission != null && permissionCodes().contains(permission.code()); }

	void requireAccess(TenantId tenantId, WorkspaceId workspaceId, Surface surface, Permission permission);
}
