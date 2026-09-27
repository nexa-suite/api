package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

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
