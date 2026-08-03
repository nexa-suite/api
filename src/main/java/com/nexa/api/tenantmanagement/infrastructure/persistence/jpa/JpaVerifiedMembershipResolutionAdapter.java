package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import com.nexa.api.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationResolutionPort;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationResolutionRequest;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JpaVerifiedMembershipResolutionAdapter implements VerifiedMembershipResolutionPort {
	private final WorkspaceMembershipJpaRepository memberships;
	private final WorkspaceJpaRepository workspaces;
	private final TenantJpaRepository tenants;
	private final WorkspaceMembershipRoleJpaRepository roleAssignments;
	private final AuthorizationResolutionPort authorization;

	public JpaVerifiedMembershipResolutionAdapter(WorkspaceMembershipJpaRepository memberships,
			WorkspaceJpaRepository workspaces, TenantJpaRepository tenants, WorkspaceMembershipRoleJpaRepository roleAssignments) {
		this(memberships, workspaces, tenants, roleAssignments,
				request -> com.nexa.api.tenantmanagement.domain.model.access.EffectiveAuthorization.fixed(
						request.fixedRoles(), request.authorizationVersion()));
	}

	@Autowired
	public JpaVerifiedMembershipResolutionAdapter(WorkspaceMembershipJpaRepository memberships,
			WorkspaceJpaRepository workspaces, TenantJpaRepository tenants, WorkspaceMembershipRoleJpaRepository roleAssignments,
			AuthorizationResolutionPort authorization) {
		this.memberships = memberships;
		this.workspaces = workspaces;
		this.tenants = tenants;
		this.roleAssignments = roleAssignments;
		this.authorization = authorization;
	}

	@Override
	public Optional<VerifiedMembership> resolve(UserId userId, TenantId tenantId, WorkspaceId workspaceId) {
		try {
			UUID user = userId.value();
			UUID tenant = tenantId.value();
			UUID workspace = workspaceId.value();
			return memberships.findForScope(user, tenant, workspace).flatMap(m -> workspaces.findById(workspace)
				.flatMap(w -> tenants.findById(tenant).map(t -> {
					MembershipId membershipId = new MembershipId(m.getId().toString());
					UserId memberUserId = new UserId(m.getUserId().toString());
					TenantId memberTenantId = new TenantId(t.getId().toString());
					WorkspaceId memberWorkspaceId = new WorkspaceId(w.getId().toString());
					java.util.Set<MembershipRole> fixedRoles = rolesFor(m);
					var effective = authorization.resolve(new AuthorizationResolutionRequest(membershipId, memberUserId,
						memberTenantId, memberWorkspaceId, m.getMembershipType(), fixedRoles, m.getVersion()));
					Membership resolved = new Membership(membershipId, memberUserId, memberTenantId, memberWorkspaceId, fixedRoles,
						effective.roleCodes(), effective.roleDefinitionIds(), MembershipStatus.from(m.getStatus()), effective.authorizationVersion());
					return new VerifiedMembership(resolved, TenantStatus.from(t.getStatus()), WorkspaceStatus.from(w.getStatus()), effective);
				})));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private java.util.Set<MembershipRole> rolesFor(WorkspaceMembershipJpaEntity membership) {
		if ("BUYER".equals(membership.getMembershipType())) return java.util.Set.of(MembershipRole.BUYER);
		return roleAssignments.findByMembershipId(membership.getId()).stream()
				.map(value -> MembershipRole.from(value.getRole())).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}
}
