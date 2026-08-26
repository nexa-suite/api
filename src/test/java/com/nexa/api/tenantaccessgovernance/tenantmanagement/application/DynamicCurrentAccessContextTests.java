package com.nexa.api.tenantaccessgovernance.tenantmanagement.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicCurrentAccessContextTests {
	@Test
	void customRoleUsesTypedUnionAndAuthorizationVersionOnEveryContext() {
		TenantId tenant = TenantId.random();
		WorkspaceId workspace = WorkspaceId.random();
		RoleDefinition role = RoleDefinition.custom(tenant, workspace, "sales.viewer", "Sales viewer", "",
				Set.of(PermissionKey.SALES_DASHBOARD_READ), UserId.random(), Instant.EPOCH);
		EffectiveAuthorization authorization = EffectiveAuthorization.of(Set.of(role), Set.of(), 7);
		Membership membership = new Membership(MembershipId.random(), UserId.random(), tenant, workspace, Set.of(),
				Set.of(role.code()), Set.of(role.id().toString()), MembershipStatus.ACTIVE, 7);
		CurrentAccessContext context = CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
				WorkspaceStatus.ACTIVE, authorization), Surface.PLATFORM);

		assertThat(context.roleCodes()).containsExactly("sales.viewer");
		assertThat(context.roleDefinitionIds()).containsExactly(role.id().toString());
		assertThat(context.authorizationVersion()).isEqualTo(7);
		assertThat(context.allows(PermissionKey.SALES_DASHBOARD_READ)).isTrue();
		assertThat(context.allows(Permission.CATALOG_MANAGE)).isFalse();
	}
}
