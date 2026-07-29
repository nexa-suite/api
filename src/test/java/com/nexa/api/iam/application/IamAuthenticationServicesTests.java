package com.nexa.api.iam.application;

import com.nexa.api.iam.application.exception.InvalidCredentialsException;
import com.nexa.api.iam.application.exception.RefreshTokenReuseDetectedException;
import com.nexa.api.iam.application.exception.SessionNotFoundException;
import com.nexa.api.iam.application.model.AccessPolicy;
import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.CurrentSession;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.application.model.RefreshRotation;
import com.nexa.api.iam.application.model.RefreshSessionCommand;
import com.nexa.api.iam.application.model.SessionRecord;
import com.nexa.api.iam.application.model.SignInCommand;
import com.nexa.api.iam.application.model.SignOutCommand;
import com.nexa.api.iam.application.model.StoredUserAccount;
import com.nexa.api.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.iam.application.port.out.SessionPort;
import com.nexa.api.iam.application.port.out.UserAccountQueryPort;
import com.nexa.api.iam.application.service.CurrentSessionService;
import com.nexa.api.iam.application.service.RefreshSessionService;
import com.nexa.api.iam.application.service.SignInService;
import com.nexa.api.iam.application.service.SignOutService;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.DisplayName;
import com.nexa.api.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.iam.domain.model.useraccount.UserAccount;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.iam.domain.model.useraccount.Username;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamAuthenticationServicesTests {
	private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void signInVerifiesCredentialsAndCreatesAPlatformOrPortalSession() {
		Fixture fixture = new Fixture();
		SignInService service = fixture.signInService();

		var result = service.signIn(new SignInCommand("CARLOS@ICISA.PE", "correct", ClientSurface.PLATFORM));

		assertThat(result.surface()).isEqualTo(ClientSurface.PLATFORM);
		assertThat(result.role()).isEqualTo("commercial");
		assertThat(result.permissions()).containsExactlyInAnyOrder("catalog:read", "sales:read");
		assertThat(result.accessToken()).isEqualTo("access-1");
		assertThat(fixture.passwords.checked).isTrue();
		assertThat(fixture.sessions.current()).isPresent();
	}

	@Test
	void signInRejectsInvalidCredentialsAndInactiveAccounts() {
		Fixture invalidPasswordFixture = new Fixture();
		invalidPasswordFixture.passwords.matches = false;
		assertThatThrownBy(() -> invalidPasswordFixture.signInService()
				.signIn(new SignInCommand("carlos", "wrong", ClientSurface.PLATFORM)))
				.isInstanceOf(InvalidCredentialsException.class);

		Fixture inactiveFixture = new Fixture();
		inactiveFixture.account.suspend();
		assertThatThrownBy(() -> inactiveFixture.signInService()
				.signIn(new SignInCommand("carlos", "correct", ClientSurface.PLATFORM)))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void refreshRotatesTokenAndReuseRevokesTheEntireFamily() {
		Fixture fixture = new Fixture();
		var first = fixture.signInService().signIn(new SignInCommand("carlos", "correct", ClientSurface.PORTAL));
		var refresh = new RefreshSessionService(fixture.sessions, fixture.policies, fixture.tokens, CLOCK);

		var rotated = refresh.refresh(new RefreshSessionCommand(first.refreshToken()));

		assertThat(rotated.refreshToken()).isEqualTo("refresh-2");
		assertThat(fixture.sessions.revokedFamilies).isEmpty();
		assertThatThrownBy(() -> refresh.refresh(new RefreshSessionCommand(first.refreshToken())))
				.isInstanceOf(RefreshTokenReuseDetectedException.class);
		assertThat(fixture.sessions.revokedFamilies).containsExactly(firstFamily(fixture));
	}

	@Test
	void signOutIsIdempotentAndCurrentSessionRequiresAnActiveToken() {
		Fixture fixture = new Fixture();
		var first = fixture.signInService().signIn(new SignInCommand("carlos", "correct", ClientSurface.PLATFORM));
		var signOut = new SignOutService(fixture.sessions, CLOCK);
		signOut.signOut(new SignOutCommand(first.accessToken()));
		signOut.signOut(new SignOutCommand(first.accessToken()));

		assertThatThrownBy(() -> new CurrentSessionService(fixture.sessions, CLOCK)
				.currentSession(new com.nexa.api.iam.application.model.CurrentSessionQuery(first.accessToken())))
				.isInstanceOf(SessionNotFoundException.class);
		assertThat(fixture.sessions.revokedSessionIds).contains(firstSession(fixture));
	}

	private static SessionId firstSession(Fixture fixture) { return fixture.sessions.started.get(0).session().id(); }
	private static RefreshTokenFamilyId firstFamily(Fixture fixture) { return fixture.sessions.started.get(0).session().refreshTokenFamilyId(); }

	private static final class Fixture {
		private final UserAccount account = UserAccount.create(new UserAccountId("user-1"), new Username("carlos"),
				new EmailAddress("carlos@icisa.pe"), new DisplayName("Carlos Rios"));
		private final FakePasswords passwords = new FakePasswords();
		private final FakePolicies policies = new FakePolicies();
		private final FakeTokens tokens = new FakeTokens();
		private final FakeSessions sessions = new FakeSessions();

		private SignInService signInService() {
			return new SignInService((UserAccountQueryPort) login -> Optional.of(new StoredUserAccount(account, "encoded")),
				passwords, policies, tokens, sessions, CLOCK);
		}
	}

	private static final class FakePasswords implements PasswordVerificationPort {
		private boolean matches = true;
		private boolean checked;

		@Override
		public boolean matches(String rawPassword, String encodedPassword) {
			checked = true;
			return matches && "correct".equals(rawPassword) && "encoded".equals(encodedPassword);
		}
	}

	private static final class FakePolicies implements AccessPolicyPort {
		@Override
		public Optional<AccessPolicy> findFor(UserAccountId id, ClientSurface surface) {
			return Optional.of(new AccessPolicy(surface, "commercial", Set.of("catalog:read", "sales:read")));
		}
	}

	private static final class FakeTokens implements AuthenticationTokenPort {
		private int sequence;

		@Override
		public IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt) {
			sequence++;
			return new IssuedAuthenticationTokens("access-" + sequence, "refresh-" + sequence, issuedAt,
					issuedAt.plusSeconds(30), issuedAt.plusSeconds(300));
		}
	}

	private static final class FakeSessions implements SessionPort {
		private final Map<String, SessionRecord> byAccess = new HashMap<>();
		private final Map<String, SessionRecord> byRefresh = new HashMap<>();
		private final Set<String> usedRefreshTokens = new HashSet<>();
		private final List<SessionRecord> started = new java.util.ArrayList<>();
		private final Set<SessionId> revokedSessionIds = new HashSet<>();
		private final Set<RefreshTokenFamilyId> revokedFamilies = new HashSet<>();

		@Override
		public SessionRecord start(AuthenticationSession session, AuthenticationSubject subject, IssuedAuthenticationTokens tokens) {
			SessionRecord record = new SessionRecord(session, subject, tokens);
			started.add(record);
			byAccess.put(record.accessToken(), record);
			byRefresh.put(record.refreshToken(), record);
			return record;
		}

		@Override
		public Optional<SessionRecord> findByAccessToken(String accessToken) { return Optional.ofNullable(byAccess.get(accessToken)); }

		@Override
		public Optional<SessionRecord> findByRefreshToken(String refreshToken) { return Optional.ofNullable(byRefresh.get(refreshToken)); }

		@Override
		public RefreshRotation rotateRefreshToken(String presentedRefreshToken, SessionRecord replacement, Instant rotatedAt) {
			if (usedRefreshTokens.contains(presentedRefreshToken)) return RefreshRotation.reused();
			SessionRecord current = byRefresh.get(presentedRefreshToken);
			if (current == null) return RefreshRotation.invalid();
			usedRefreshTokens.add(presentedRefreshToken);
			byAccess.remove(current.accessToken());
			byAccess.put(replacement.accessToken(), replacement);
			byRefresh.put(replacement.refreshToken(), replacement);
			started.add(replacement);
			return RefreshRotation.rotated(replacement);
		}

		@Override
		public void revoke(SessionId sessionId, Instant revokedAt) {
			revokedSessionIds.add(sessionId);
			byAccess.values().stream().filter(record -> record.session().id().equals(sessionId))
					.forEach(record -> record.session().revoke(revokedAt));
		}

		@Override
		public void revokeFamily(RefreshTokenFamilyId familyId, Instant revokedAt) {
			revokedFamilies.add(familyId);
			started.stream().filter(record -> record.session().refreshTokenFamilyId().equals(familyId))
					.forEach(record -> record.session().revoke(revokedAt));
		}

		private Optional<SessionRecord> current() { return started.stream().reduce((first, second) -> second); }
	}
}
