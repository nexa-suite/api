package com.nexa.api.tenantmanagement.application.model;

import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionPolicy;
import com.nexa.api.tenantmanagement.domain.model.access.RoleSurfacePolicy;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;

import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Application-facing access snapshot created only from a verified membership.
 * It is the source of tenant/workspace/surface scope for downstream use cases.
 */
public final class CurrentAccessContext implements AccessContext {
	private final VerifiedMembership verifiedMembership;
	private final Surface surface;
	private final Set<Permission> permissions;

	private CurrentAccessContext(VerifiedMembership verifiedMembership, Surface surface) {
		this.verifiedMembership = Objects.requireNonNull(verifiedMembership, "Verified membership is required");
		this.surface = Objects.requireNonNull(surface, "Surface is required");
		if (!verifiedMembership.isAccessible()) {
			throw new AccessPolicyViolation("Tenant workspace membership is not active");
		}
		if (!RoleSurfacePolicy.allows(roles(), surface)) throw new AccessPolicyViolation("Membership roles are not allowed on requested surface");
		this.permissions = PermissionPolicy.permissionsFor(roles());
	}

	public static CurrentAccessContext from(VerifiedMembership verifiedMembership, Surface surface) {
		return new CurrentAccessContext(verifiedMembership, surface);
	}

	public VerifiedMembership verifiedMembership() {
		return verifiedMembership;
	}

	@Override
	public UserId userId() {
		return verifiedMembership.membership().userId();
	}

	@Override
	public TenantId tenantId() {
		return verifiedMembership.membership().tenantId();
	}

	@Override
	public WorkspaceId workspaceId() {
		return verifiedMembership.membership().workspaceId();
	}

	@Override
	public MembershipId membershipId() {
		return verifiedMembership.membership().id();
	}

	public Set<MembershipRole> roles() { return verifiedMembership.membership().roles(); }

	public boolean hasRole(MembershipRole role) { return roles().contains(Objects.requireNonNull(role)); }

	@Override
	public Surface surface() {
		return surface;
	}

	@Override
	public Set<Permission> permissions() {
		return permissions;
	}

	@Override
	public boolean allows(Permission permission) {
		return permissions.contains(Objects.requireNonNull(permission, "Permission is required"));
	}

	public boolean hasPermission(Permission permission) {
		return allows(permission);
	}

	public TenantStatus tenantStatus() {
		return verifiedMembership.tenantStatus();
	}

	public WorkspaceStatus workspaceStatus() {
		return verifiedMembership.workspaceStatus();
	}

	public MembershipStatus membershipStatus() {
		return verifiedMembership.membership().status();
	}

	public boolean isSameTenant(TenantId requestedTenantId) {
		return tenantId().equals(Objects.requireNonNull(requestedTenantId, "Tenant id is required"));
	}

	public boolean isSameWorkspace(WorkspaceId requestedWorkspaceId) {
		return workspaceId().equals(Objects.requireNonNull(requestedWorkspaceId, "Workspace id is required"));
	}

	public void requireTenant(TenantId requestedTenantId) {
		if (!isSameTenant(requestedTenantId)) {
			throw new AccessPolicyViolation("Requested resource is outside the current tenant");
		}
	}

	public void requireTenantScope(TenantId requestedTenantId) {
		requireTenant(requestedTenantId);
	}

	public void requireWorkspace(WorkspaceId requestedWorkspaceId) {
		if (!isSameWorkspace(requestedWorkspaceId)) {
			throw new AccessPolicyViolation("Requested resource is outside the current workspace");
		}
	}

	public void requireWorkspaceScope(WorkspaceId requestedWorkspaceId) {
		requireWorkspace(requestedWorkspaceId);
	}

	public void requireSurface(Surface requestedSurface) {
		if (!surface().equals(Objects.requireNonNull(requestedSurface, "Surface is required"))) {
			throw new AccessPolicyViolation("Requested resource is outside the current surface");
		}
	}

	public void requirePermission(Permission permission) {
		if (!allows(permission)) {
			throw new AccessPolicyViolation("Membership role does not have the requested permission");
		}
	}

	@Override
	public void requireAccess(TenantId requestedTenantId, WorkspaceId requestedWorkspaceId,
			Surface requestedSurface, Permission permission) {
		requireTenant(requestedTenantId);
		requireWorkspace(requestedWorkspaceId);
		requireSurface(requestedSurface);
		requirePermission(permission);
	}
}
