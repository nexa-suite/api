package com.nexa.api.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

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
	private final MembershipStatus status;

	public Membership(MembershipId id, UserId userId, TenantId tenantId, WorkspaceId workspaceId,
			MembershipRole role, MembershipStatus status) {
		this(id, userId, tenantId, workspaceId, Set.of(role), status);
	}

	public Membership(MembershipId id, UserId userId, TenantId tenantId, WorkspaceId workspaceId,
			Set<MembershipRole> roles, MembershipStatus status) {
		this.id = Objects.requireNonNull(id, "Membership id is required");
		this.userId = Objects.requireNonNull(userId, "Membership user id is required");
		this.tenantId = Objects.requireNonNull(tenantId, "Membership tenant id is required");
		this.workspaceId = Objects.requireNonNull(workspaceId, "Membership workspace id is required");
		if (roles == null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("At least one membership role is required");
		}
		this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
		this.status = Objects.requireNonNull(status, "Membership status is required");
	}

	public MembershipId id() { return id; }
	public UserId userId() { return userId; }
	public TenantId tenantId() { return tenantId; }
	public WorkspaceId workspaceId() { return workspaceId; }
	/** Compatibility accessor. Authorization must use roles() or hasRole(). */
	public MembershipRole role() { return roles.iterator().next(); }
	public Set<MembershipRole> roles() { return roles; }
	public boolean hasRole(MembershipRole role) { return roles.contains(Objects.requireNonNull(role)); }
	public MembershipStatus status() { return status; }

	public boolean isActive() {
		return status.isActive();
	}

	public boolean belongsTo(UserId requestedUserId, TenantId requestedTenantId, WorkspaceId requestedWorkspaceId) {
		return userId.equals(requestedUserId)
				&& tenantId.equals(requestedTenantId)
				&& workspaceId.equals(requestedWorkspaceId);
	}
}
