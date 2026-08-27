package com.nexa.api.tenantaccessgovernance.iam.domain.model.session;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationSessionTests {
	private static final Instant CREATED = Instant.parse("2026-07-28T12:00:00Z");
	private static final Instant EXPIRES = CREATED.plusSeconds(60);

	@Test
	void sessionIsActiveUntilExpirationAndCanBeRevoked() {
		AuthenticationSession session = AuthenticationSession.start(new SessionId("session-1"), new UserAccountId("user-1"),
				ClientSurface.PORTAL, new RefreshTokenFamilyId("family-1"), CREATED, EXPIRES);

		assertThat(session.isActive(CREATED)).isTrue();
		assertThat(session.isActive(EXPIRES.minusNanos(1))).isTrue();
		assertThat(session.isActive(EXPIRES)).isFalse();
		session.revoke(CREATED.plusSeconds(10));
		assertThat(session.status()).isEqualTo(AuthenticationSessionStatus.REVOKED);
		assertThat(session.isActive(CREATED.plusSeconds(11))).isFalse();
	}

	@Test
	void expirationMustFollowCreation() {
		assertThatThrownBy(() -> AuthenticationSession.start(new SessionId("session-1"), new UserAccountId("user-1"),
				ClientSurface.PLATFORM, new RefreshTokenFamilyId("family-1"), CREATED, CREATED))
				.isInstanceOf(SessionInvariantViolation.class);
	}
}
