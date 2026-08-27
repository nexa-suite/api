package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class Membership {
	private final MembershipId id;
	private final UserId userId;
	private final TenantId tenantId;
	private final WorkspaceId workspaceId;
	private final Set<MembershipRole> roles;
	private final Set<String> roleCodes;
	private final Set<String> roleDefinitionIds;
	private final MembershipStatus status;
	private final long authorizationVersion;

	public Membership(MembershipId id, UserId userId, TenantId tenantId, WorkspaceId workspaceId,
			Set<MembershipRole> roles, MembershipStatus status) {
		this(id, userId, tenantId, workspaceId, roles, defaultRoleCodes(roles), defaultRoleDefinitionIds(roles), status, 0);
	}

	public Membership(MembershipId id, UserId userId, TenantId tenantId, WorkspaceId workspaceId,
			Set<MembershipRole> roles, MembershipStatus status, long authorizationVersion) {
		this(id, userId, tenantId, workspaceId, roles, defaultRoleCodes(roles), defaultRoleDefinitionIds(roles), status,
				authorizationVersion);
	}

	/**
	 * Creates a membership snapshot with both legacy fixed roles and dynamic
	 * role assignments. The fixed enum set remains only a compatibility view;
	 * authorization uses the canonical role codes and definition ids.
	 */
	public Membership(MembershipId id, UserId userId, TenantId tenantId, WorkspaceId workspaceId,
			Set<MembershipRole> roles, Set<String> roleCodes, Set<String> roleDefinitionIds,
			MembershipStatus status, long authorizationVersion) {
		this.id = Objects.requireNonNull(id, "Membership id is required");
		this.userId = Objects.requireNonNull(userId, "Membership user id is required");
		this.tenantId = Objects.requireNonNull(tenantId, "Membership tenant id is required");
		this.workspaceId = Objects.requireNonNull(workspaceId, "Membership workspace id is required");
		if (roles == null || roles.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Membership roles cannot contain null values");
		}
		this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
		this.roleCodes = immutableTextSet(roleCodes, "Role codes");
		this.roleDefinitionIds = immutableTextSet(roleDefinitionIds, "Role definition ids");
		if (this.roleCodes.isEmpty() || this.roleDefinitionIds.isEmpty()) {
			throw new IllegalArgumentException("At least one role assignment is required");
		}
		this.status = Objects.requireNonNull(status, "Membership status is required");
		if (authorizationVersion < 0) throw new IllegalArgumentException("Authorization version cannot be negative");
		this.authorizationVersion = authorizationVersion;
	}

	public MembershipId id() { return id; }
	public UserId userId() { return userId; }
	public TenantId tenantId() { return tenantId; }
	public WorkspaceId workspaceId() { return workspaceId; }
	public Set<MembershipRole> roles() { return roles; }
	public Set<String> roleCodes() { return roleCodes; }
	public Set<String> roleDefinitionIds() { return roleDefinitionIds; }
	public boolean hasRole(MembershipRole role) { return roles.contains(Objects.requireNonNull(role)); }
	public boolean hasRoleCode(String roleCode) { return roleCode != null && roleCodes.stream().anyMatch(roleCode::equalsIgnoreCase); }
	public MembershipStatus status() { return status; }
	public long authorizationVersion() { return authorizationVersion; }

	public boolean isActive() {
		return status.isActive();
	}

	public boolean belongsTo(UserId requestedUserId, TenantId requestedTenantId, WorkspaceId requestedWorkspaceId) {
		return userId.equals(requestedUserId)
				&& tenantId.equals(requestedTenantId)
				&& workspaceId.equals(requestedWorkspaceId);
	}

	private static Set<String> defaultRoleCodes(Set<MembershipRole> roles) {
		if (roles == null || roles.isEmpty()) throw new IllegalArgumentException("At least one membership role is required");
		return roles.stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static Set<String> defaultRoleDefinitionIds(Set<MembershipRole> roles) {
		if (roles == null || roles.isEmpty()) throw new IllegalArgumentException("At least one membership role is required");
		return roles.stream().map(role -> RoleDefinitionId.system(role.name()).toString())
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static Set<String> immutableTextSet(Set<String> values, String label) {
		if (values == null) throw new IllegalArgumentException(label + " are required");
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " cannot contain blank values");
			normalized.add(value.trim());
		}
		return Collections.unmodifiableSet(normalized);
	}
}
