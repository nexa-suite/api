package com.nexa.api.tenantmanagement.domain;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.tenant.TenantName;
import com.nexa.api.tenantmanagement.domain.model.tenant.TenantSlug;
import com.nexa.api.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceName;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceSlug;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIdentityAndNormalizationTests {
	@Test
	void identityValueObjectsAcceptCanonicalUuidAndKeepValueObjectEquality() {
		UUID raw = UUID.fromString("3f4f1c2e-b5ea-4f0e-9d24-1fddc5f56b27");

		TenantId tenantId = new TenantId(raw);
		WorkspaceId workspaceId = new WorkspaceId(raw.toString().toUpperCase());
		MembershipId membershipId = MembershipId.random();
		UserId userId = UserId.from(raw.toString());

		assertThat(tenantId.value()).isEqualTo(raw);
		assertThat(workspaceId).isEqualTo(new WorkspaceId(raw));
		assertThat(userId.toString()).isEqualTo(raw.toString());
		assertThat(membershipId.value()).isNotNull();
	}

	@Test
	void identityValueObjectsRejectMissingOrNonCanonicalUuid() {
		assertThatThrownBy(() -> new TenantId((UUID) null))
				.isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> new WorkspaceId("not-a-uuid"))
				.isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> new MembershipId("1-1-1-1-1"))
				.isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> new UserId(" "))
				.isInstanceOf(TenantManagementInvariantViolation.class);
	}

	@Test
	void namesTrimAndCollapseWhitespaceWithoutDiscardingCapitalization() {
		assertThat(new TenantName("  Clínica   Central  ").value()).isEqualTo("Clínica Central");
		assertThat(new WorkspaceName("  Lima\t Cold\nChain  ").value()).isEqualTo("Lima Cold Chain");
		assertThatThrownBy(() -> new TenantName(" ")).isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> new WorkspaceName("x".repeat(161)))
				.isInstanceOf(TenantManagementInvariantViolation.class);
	}

	@Test
	void slugsNormalizeAccentsSeparatorsCaseAndBoundaries() {
		assertThat(new TenantSlug("  Clínica & Norte  ").value()).isEqualTo("clinica-norte");
		assertThat(WorkspaceSlug.fromName("Lima / Cold Chain").value()).isEqualTo("lima-cold-chain");
		assertThat(new WorkspaceSlug("icisa")).isEqualTo(new WorkspaceSlug("ICISA"));
		assertThatThrownBy(() -> new TenantSlug("a")).isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> new WorkspaceSlug("!@#")).isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> new TenantSlug("a".repeat(64))).isInstanceOf(TenantManagementInvariantViolation.class);
	}

	@Test
	void statusesParseLegacyExternalRepresentationAndOnlyActiveIsAccessible() {
		assertThat(TenantStatus.from("pending-review")).isEqualTo(TenantStatus.PENDING_REVIEW);
		assertThat(WorkspaceStatus.from("SUSPENDED")).isEqualTo(WorkspaceStatus.SUSPENDED);
		assertThat(TenantStatus.ACTIVE.isAccessible()).isTrue();
		assertThat(WorkspaceStatus.PENDING_REVIEW.isAccessible()).isFalse();
	}
}
