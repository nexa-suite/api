package com.nexa.api.tenantmanagement.domain;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionPolicy;
import com.nexa.api.tenantmanagement.domain.model.access.RoleSurfacePolicy;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
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
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessPolicyTests {
	@Test
	void membershipRoleIsTheCanonicalRoleSet() {
		assertThat(MembershipRole.values()).containsExactly(
				MembershipRole.COMPANY_OWNER,
				MembershipRole.SALES,
				MembershipRole.WAREHOUSE,
				MembershipRole.LOGISTICS,
				MembershipRole.BUYER);
		assertThat(MembershipRole.from("company owner")).isEqualTo(MembershipRole.COMPANY_OWNER);
		assertThatThrownBy(() -> MembershipRole.from("admin"))
				.isInstanceOf(TenantManagementInvariantViolation.class);
	}

	@Test
	void roleSurfacePolicySeparatesPlatformAndPortal() {
		assertThat(RoleSurfacePolicy.allows(MembershipRole.COMPANY_OWNER, Surface.PLATFORM)).isTrue();
		assertThat(RoleSurfacePolicy.allows(MembershipRole.SALES, Surface.PLATFORM)).isTrue();
		assertThat(RoleSurfacePolicy.allows(MembershipRole.WAREHOUSE, Surface.PLATFORM)).isTrue();
		assertThat(RoleSurfacePolicy.allows(MembershipRole.LOGISTICS, Surface.PLATFORM)).isTrue();
		assertThat(RoleSurfacePolicy.allows(MembershipRole.BUYER, Surface.PORTAL)).isTrue();
		assertThat(RoleSurfacePolicy.allows(MembershipRole.BUYER, Surface.PLATFORM)).isFalse();
		assertThatThrownBy(() -> RoleSurfacePolicy.requireAllowed(MembershipRole.SALES, Surface.PORTAL))
				.isInstanceOf(AccessPolicyViolation.class);
	}

	@Test
	void onePermissionPolicyMapsRequiredCapabilitiesByMembershipRole() {
		assertThat(PermissionPolicy.permissionsFor(MembershipRole.COMPANY_OWNER))
				.containsExactlyInAnyOrder(Permission.values());
		assertThat(PermissionPolicy.permissionsFor(MembershipRole.SALES))
				.containsExactlyInAnyOrder(
						Permission.CATALOG_READ, Permission.TENANT_READ, Permission.SALES_READ,
						Permission.SALES_WRITE, Permission.INVOICING_READ, Permission.LOGISTICS_READ);
		assertThat(PermissionPolicy.permissionsFor(MembershipRole.BUYER))
				.containsExactlyInAnyOrder(Permission.CATALOG_READ, Permission.SALES_BUYER_READ,
						Permission.SALES_BUYER_WRITE, Permission.LOGISTICS_BUYER_READ, Permission.INVOICING_BUYER_READ);
		assertThat(PermissionPolicy.allows(MembershipRole.WAREHOUSE, Permission.WAREHOUSE_WRITE)).isTrue();
		assertThat(PermissionPolicy.allows(MembershipRole.LOGISTICS, Permission.TENANT_MANAGE)).isFalse();
	}

	@Test
	void verifiedMembershipRequiresActiveMembershipAndScopes() {
		TenantId tenantId = TenantId.random();
		WorkspaceId workspaceId = WorkspaceId.random();
		Membership membership = new Membership(MembershipId.random(), UserId.random(), tenantId, workspaceId,
				MembershipRole.BUYER, MembershipStatus.ACTIVE);
		VerifiedMembership verified = new VerifiedMembership(membership, TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE);

		assertThat(verified.isAccessible()).isTrue();
		assertThat(verified.belongsTo(membership.userId(), tenantId, workspaceId)).isTrue();
		assertThat(verified.belongsTo(UserId.random(), tenantId, workspaceId)).isFalse();
		assertThat(new VerifiedMembership(membership, TenantStatus.SUSPENDED, WorkspaceStatus.ACTIVE).isAccessible())
				.isFalse();
		assertThat(Set.copyOf(PermissionPolicy.permissionsFor(MembershipRole.BUYER))).isUnmodifiable();
	}
}
