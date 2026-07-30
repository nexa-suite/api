package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import com.nexa.api.iam.application.model.AccessPolicy;
import com.nexa.api.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.iam.infrastructure.persistence.jpa.UserAccountJpaEntity;
import com.nexa.api.iam.infrastructure.persistence.jpa.UserAccountJpaRepository;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionPolicy;
import com.nexa.api.tenantmanagement.domain.model.access.RoleSurfacePolicy;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JpaAccessPolicyAdapter implements AccessPolicyPort {
	private final UserAccountJpaRepository users;
	private final WorkspaceMembershipJpaRepository memberships;
	private final WorkspaceJpaRepository workspaces;
	private final TenantJpaRepository tenants;

	public JpaAccessPolicyAdapter(UserAccountJpaRepository users, WorkspaceMembershipJpaRepository memberships,
			WorkspaceJpaRepository workspaces, TenantJpaRepository tenants) {
		this.users = users;
		this.memberships = memberships;
		this.workspaces = workspaces;
		this.tenants = tenants;
	}

	@Override
	public Optional<AccessPolicy> findFor(UserAccountId userAccountId, ClientSurface surface) {
		return Optional.empty();
	}

	@Override
	public Optional<AccessPolicy> findFor(UserAccountId userAccountId, String workspaceSlug, ClientSurface surface) {
		if (workspaceSlug == null || workspaceSlug.isBlank()) return Optional.empty();
		UUID userId;
		try { userId = UUID.fromString(userAccountId.value()); } catch (IllegalArgumentException exception) { return Optional.empty(); }
		return memberships.findForUserAndWorkspaceSlug(userId, workspaceSlug.toLowerCase(java.util.Locale.ROOT))
				.flatMap(membership -> {
					if (!"ACTIVE".equals(membership.getStatus())) return Optional.empty();
					return workspaces.findById(membership.getWorkspaceId()).flatMap(workspace -> tenants.findById(workspace.getTenantId())
							.flatMap(tenant -> users.findById(userId).flatMap(user -> policy(user, membership, workspace, tenant, surface))));
				});
	}

	private Optional<AccessPolicy> policy(UserAccountJpaEntity user, WorkspaceMembershipJpaEntity membership,
			WorkspaceJpaEntity workspace, TenantJpaEntity tenant, ClientSurface surface) {
		MembershipRole role;
		try { role = MembershipRole.from(membership.getRole()); } catch (RuntimeException exception) { return Optional.empty(); }
		Surface requestedSurface = Surface.valueOf(surface.name());
		if (!RoleSurfacePolicy.allows(role, requestedSurface) || !"ACTIVE".equals(workspace.getStatus()) || !"ACTIVE".equals(tenant.getStatus())) {
			return Optional.empty();
		}
		var permissions = PermissionPolicy.permissionsFor(role).stream().map(Permission::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
		return Optional.of(new AccessPolicy(surface, role.name(), permissions, tenant.getId().toString(), tenant.getSlug(),
				workspace.getId().toString(), workspace.getSlug(), membership.getId().toString(), user.getDisplayName(), user.getPreferredLanguage()));
	}
}
