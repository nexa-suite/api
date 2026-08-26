package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.invitation.InvitationExpiry;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.invitation.InvitationStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.invitation.InvitationTokenHash;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.invitation.OrganizationInvitation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationInvitationLifecycleTests {
	private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final InvitationTokenHash FIRST_HASH = new InvitationTokenHash("a".repeat(64));
	private static final InvitationTokenHash SECOND_HASH = new InvitationTokenHash("b".repeat(64));

	@Test
	void resendRotatesTheHashAndExpiryOnlyWhilePending() {
		OrganizationInvitation invitation = pending(NOW.plusSeconds(3600));

		invitation.resend(SECOND_HASH, new InvitationExpiry(NOW.plusSeconds(7200)), CLOCK);

		assertThat(invitation.status()).isEqualTo(InvitationStatus.PENDING);
		assertThat(invitation.hasTokenHash(FIRST_HASH)).isFalse();
		assertThat(invitation.hasTokenHash(SECOND_HASH)).isTrue();
		assertThat(invitation.expiry().value()).isEqualTo(NOW.plusSeconds(7200));
	}

	@Test
	void invitationTransitionsAreSingleUseAndExpiryIsEnforced() {
		OrganizationInvitation accepted = pending(NOW.plusSeconds(3600));
		accepted.accept(CLOCK);
		assertThat(accepted.status()).isEqualTo(InvitationStatus.ACCEPTED);
		assertThatThrownBy(() -> accepted.accept(CLOCK)).isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> accepted.revoke(CLOCK)).isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> accepted.resend(SECOND_HASH, new InvitationExpiry(NOW.plusSeconds(7200)), CLOCK))
				.isInstanceOf(TenantManagementInvariantViolation.class);

		OrganizationInvitation expired = pending(NOW.plusSeconds(1));
		Clock afterExpiry = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
		expired.expire(afterExpiry);
		assertThat(expired.status()).isEqualTo(InvitationStatus.EXPIRED);
		assertThatThrownBy(() -> expired.accept(afterExpiry)).isInstanceOf(TenantManagementInvariantViolation.class);
	}

	@Test
	void aggregateRejectsBuyerRolesAndInvalidIdentity() {
		assertThatThrownBy(() -> OrganizationInvitation.pending(UUID.randomUUID(), new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()),
				"member@example.test", "Member", FIRST_HASH, Set.of(MembershipRole.BUYER), new InvitationExpiry(NOW.plusSeconds(3600)), new MembershipId(UUID.randomUUID())))
				.isInstanceOf(TenantManagementInvariantViolation.class);
		assertThatThrownBy(() -> OrganizationInvitation.pending(UUID.randomUUID(), new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()),
				"not-an-email", "Member", FIRST_HASH, Set.of(MembershipRole.SALES), new InvitationExpiry(NOW.plusSeconds(3600)), new MembershipId(UUID.randomUUID())))
				.isInstanceOf(TenantManagementInvariantViolation.class);
	}

	private static OrganizationInvitation pending(Instant expiry) {
		return OrganizationInvitation.pending(UUID.randomUUID(), new TenantId(UUID.randomUUID()), new WorkspaceId(UUID.randomUUID()),
				" Member@Example.Test ", " Member ", FIRST_HASH, Set.of(MembershipRole.SALES, MembershipRole.WAREHOUSE),
				new InvitationExpiry(expiry), new MembershipId(UUID.randomUUID()));
	}
}
