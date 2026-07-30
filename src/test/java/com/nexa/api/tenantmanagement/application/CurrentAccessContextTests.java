package com.nexa.api.tenantmanagement.application;

import com.nexa.api.tenantmanagement.application.exception.InaccessibleTenantException;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
import com.nexa.api.tenantmanagement.application.service.ResolveCurrentAccessContextService;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentAccessContextTests {
	@Test
	void serviceBuildsContextFromVerifiedMembershipAndCanonicalPolicy() {
		VerifiedMembership verified = verified(MembershipRole.BUYER, MembershipStatus.ACTIVE,
				TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE);
		ResolveCurrentAccessContextService service = new ResolveCurrentAccessContextService((userId, tenantId, workspaceId) ->
				Optional.of(verified));
		CurrentAccessContext context = service.resolve(new CurrentAccessRequest(
				verified.membership().userId(), verified.membership().tenantId(), verified.membership().workspaceId(),
				Surface.PORTAL));

		assertThat(context.userId()).isEqualTo(verified.membership().userId());
		assertThat(context.tenantId()).isEqualTo(verified.membership().tenantId());
		assertThat(context.workspaceId()).isEqualTo(verified.membership().workspaceId());
		assertThat(context.role()).isEqualTo(MembershipRole.BUYER);
		assertThat(context.allows(Permission.SALES_BUYER_WRITE)).isTrue();
		assertThat(context.allows(Permission.WAREHOUSE_WRITE)).isFalse();
	}

	@Test
	void contextRejectsCrossTenantWorkspaceSurfaceAndPermissionAccess() {
		VerifiedMembership verified = verified(MembershipRole.SALES, MembershipStatus.ACTIVE,
				TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE);
		CurrentAccessContext context = CurrentAccessContext.from(verified, Surface.PLATFORM);

		assertThatThrownBy(() -> context.requireTenant(TenantId.random()))
				.isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> context.requireWorkspace(WorkspaceId.random()))
				.isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> context.requireSurface(Surface.PORTAL))
				.isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> context.requirePermission(Permission.TENANT_MANAGE))
				.isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> context.requireAccess(verified.membership().tenantId(),
					verified.membership().workspaceId(), Surface.PLATFORM, Permission.WAREHOUSE_READ))
				.isInstanceOf(AccessPolicyViolation.class);
	}

	@Test
	void resolutionUsesGenericDenialForMissingMismatchedOrInactiveMembership() {
		VerifiedMembership verified = verified(MembershipRole.SALES, MembershipStatus.ACTIVE,
				TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE);
		CurrentAccessRequest request = new CurrentAccessRequest(verified.membership().userId(),
				verified.membership().tenantId(), verified.membership().workspaceId(), Surface.PLATFORM);

		ResolveCurrentAccessContextService missing = new ResolveCurrentAccessContextService((userId, tenantId, workspaceId) ->
				Optional.empty());
		assertThatThrownBy(() -> missing.resolve(request)).isInstanceOf(InaccessibleTenantException.class)
				.hasMessage("The requested tenant workspace is not accessible");

		Membership crossTenantMembership = new Membership(MembershipId.random(), verified.membership().userId(),
				TenantId.random(), verified.membership().workspaceId(), MembershipRole.SALES, MembershipStatus.ACTIVE);
		VerifiedMembership mismatched = new VerifiedMembership(crossTenantMembership, TenantStatus.ACTIVE,
				WorkspaceStatus.ACTIVE);
		ResolveCurrentAccessContextService crossTenant = new ResolveCurrentAccessContextService((userId, tenantId, workspaceId) ->
				Optional.of(mismatched));
		assertThatThrownBy(() -> crossTenant.resolve(request)).isInstanceOf(InaccessibleTenantException.class);

		VerifiedMembership inactive = verified(MembershipRole.SALES, MembershipStatus.DISABLED,
				TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE);
		ResolveCurrentAccessContextService disabled = new ResolveCurrentAccessContextService((userId, tenantId, workspaceId) ->
				Optional.of(inactive));
		assertThatThrownBy(() -> disabled.resolve(new CurrentAccessRequest(inactive.membership().userId(),
					inactive.membership().tenantId(), inactive.membership().workspaceId(), Surface.PLATFORM)))
				.isInstanceOf(InaccessibleTenantException.class);
	}

	@Test
	void applicationSourcesHaveNoFrameworkPersistenceTransportOrJwtImports() throws Exception {
		try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/com/nexa/api/tenantmanagement/application"))) {
			String source = files.filter(path -> path.toString().endsWith(".java"))
					.map(path -> {
						try {
							return java.nio.file.Files.readString(path);
						} catch (java.io.IOException exception) {
							throw new IllegalStateException(exception);
						}
					})
					.reduce("", String::concat);
			assertThat(source).doesNotContain("org.springframework", "jakarta.persistence", "com.fasterxml.jackson",
					"java.sql", "io.jsonwebtoken", "com.auth0.jwt");
		}
	}

	private VerifiedMembership verified(MembershipRole role, MembershipStatus membershipStatus,
			TenantStatus tenantStatus, WorkspaceStatus workspaceStatus) {
		return new VerifiedMembership(new Membership(MembershipId.random(), UserId.random(), TenantId.random(),
				WorkspaceId.random(), role, membershipStatus), tenantStatus, workspaceStatus);
	}
}
