package com.nexa.api.tenantaccessgovernance.iam.application;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.InvalidAccessContextTicketException;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.SelectedAccessContextUnavailableException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketQuery;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketRecord;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextOption;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessPolicy;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationSubject;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IdentitySignInCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IdentitySignInResult;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SelectAccessContextCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SessionRecord;
import com.nexa.api.tenantaccessgovernance.iam.application.model.StoredUserAccount;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextTicketPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextDiscoveryScopePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.OpaqueSecurityTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SessionPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserAccountQueryPort;
import com.nexa.api.tenantaccessgovernance.iam.application.service.AccessContextService;
import com.nexa.api.tenantaccessgovernance.iam.application.service.CredentialAuthenticationService;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.SessionId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.DisplayName;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccount;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.Username;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessContextServiceTests {
	private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

	@Test
	void zeroContextsFailsClosedWithoutCreatingSessionOrTicket() {
		Fixture fixture = new Fixture();

		IdentitySignInResult result = fixture.service.identitySignIn(fixture.command());

		assertThat(result.outcome()).isEqualTo(IdentitySignInResult.Outcome.NO_WORK_CONTEXT);
		assertThat(result.authentication()).isNull();
		assertThat(result.accessContextTicket()).isNull();
		assertThat(result.ticketExpiresAt()).isNull();
		assertThat(fixture.sessions.started).isEmpty();
		assertThat(fixture.tickets.records).isEmpty();
		assertThat(fixture.audit.events).extracting(SecurityAuditPort.Event::type)
				.contains("IDENTITY_AUTHENTICATED_NO_WORK_CONTEXT");
	}

	@Test
	void oneContextEstablishesAScopedSessionWithoutRedundantSelection() {
		Fixture fixture = new Fixture();
		fixture.policies.setOptions(policy(ClientSurface.PLATFORM, "membership-1"));

		IdentitySignInResult result = fixture.service.identitySignIn(fixture.command());

		assertThat(result.outcome()).isEqualTo(IdentitySignInResult.Outcome.SESSION_ESTABLISHED);
		assertThat(result.authentication()).isNotNull();
		assertThat(result.authentication().tenantName()).isEqualTo("ICISA");
		assertThat(result.authentication().workspaceName()).isEqualTo("Sales");
		assertThat(fixture.sessions.started).hasSize(1);
		assertThat(fixture.tickets.records).isEmpty();
	}

	@Test
	void multipleContextsReturnFiveMinuteTicketAndListOnlyCurrentEligibleContexts() {
		Fixture fixture = new Fixture();
		fixture.policies.setOptions(policy(ClientSurface.PLATFORM, "membership-1"),
				policy(ClientSurface.PLATFORM, "membership-2"));

		IdentitySignInResult result = fixture.service.identitySignIn(fixture.command());

		assertThat(result.outcome()).isEqualTo(IdentitySignInResult.Outcome.CONTEXT_SELECTION_REQUIRED);
		assertThat(result.authentication()).isNull();
		assertThat(result.accessContextTicket()).isNotBlank();
		assertThat(result.ticketExpiresAt()).isEqualTo(NOW.plusSeconds(300));
		assertThat(result.toString()).doesNotContain(result.accessContextTicket());
		assertThat(fixture.sessions.started).isEmpty();
		assertThat(fixture.tickets.records).containsOnlyKeys(fixture.opaque.sha256(result.accessContextTicket()));
		assertThat(fixture.tickets.records.values()).allSatisfy(ticket -> {
			assertThat(ticket.ticketHash()).isNotEqualTo(result.accessContextTicket());
			assertThat(ticket.surface()).isEqualTo(ClientSurface.PLATFORM);
		});
		assertThat(fixture.audit.events.toString()).doesNotContain(result.accessContextTicket());
		assertThat(result.toString()).doesNotContain(result.accessContextTicket());

		var query = new AccessContextTicketQuery(result.accessContextTicket(), ClientSurface.PLATFORM);
		assertThat(query.toString()).doesNotContain(result.accessContextTicket());
		var contexts = fixture.service.listAccessContexts(query);
		assertThat(contexts).extracting(option -> option.membershipId())
				.containsExactly("membership-1", "membership-2");
		assertThat(contexts.getFirst().tenantName()).isEqualTo("ICISA");
	}

	@Test
	void selectionCreatesOneNewFamilyConsumesTicketAndLeavesUnrelatedSessionValid() {
		Fixture fixture = new Fixture();
		fixture.policies.setOptions(policy(ClientSurface.PLATFORM, "membership-1"),
				policy(ClientSurface.PLATFORM, "membership-2"));
		var unrelated = fixture.startUnrelatedSession();
		IdentitySignInResult identity = fixture.service.identitySignIn(fixture.command());

		var selected = fixture.service.selectAccessContext(new SelectAccessContextCommand(
				identity.accessContextTicket(), ClientSurface.PLATFORM, "membership-2"));
		assertThat(new SelectAccessContextCommand(identity.accessContextTicket(), ClientSurface.PLATFORM, "membership-2")
				.toString()).doesNotContain(identity.accessContextTicket());

		assertThat(fixture.sessions.started).hasSize(2);
		assertThat(selected.membershipId()).isEqualTo("membership-2");
		assertThat(fixture.sessions.started.get(1).session().refreshTokenFamilyId())
				.isNotEqualTo(unrelated.session().refreshTokenFamilyId());
		assertThat(fixture.sessions.revokedFamilies).isEmpty();
		assertThat(fixture.tickets.records.get(fixture.opaque.sha256(identity.accessContextTicket())).consumedAt()).isEqualTo(NOW);
		assertThatThrownBy(() -> fixture.service.selectAccessContext(new SelectAccessContextCommand(
				identity.accessContextTicket(), ClientSurface.PLATFORM, "membership-2")))
				.isInstanceOf(InvalidAccessContextTicketException.class);
		assertThat(fixture.sessions.started).hasSize(2);
	}

	@Test
	void selectionRevalidatesMembershipBeforeConsumingTicket() {
		Fixture fixture = new Fixture();
		fixture.policies.setOptions(policy(ClientSurface.PLATFORM, "membership-1"),
				policy(ClientSurface.PLATFORM, "membership-2"));
		IdentitySignInResult identity = fixture.service.identitySignIn(fixture.command());
		fixture.policies.removeMembership("membership-2");

		assertThatThrownBy(() -> fixture.service.selectAccessContext(new SelectAccessContextCommand(
				identity.accessContextTicket(), ClientSurface.PLATFORM, "membership-2")))
				.isInstanceOf(SelectedAccessContextUnavailableException.class);
		assertThat(fixture.sessions.started).isEmpty();
		assertThat(fixture.tickets.records.get(fixture.opaque.sha256(identity.accessContextTicket())).consumedAt()).isNull();
	}

	@Test
	void bearerSelectionRevokesOnlyTheInvokingFamilyAndRevalidatesCurrentContexts() {
		Fixture fixture = twoContextFixture();
		SessionRecord invoking = fixture.startSession(ClientSurface.PLATFORM, "membership-1");
		SessionRecord unrelated = fixture.startSession(ClientSurface.PLATFORM, "membership-1");

		var contexts = fixture.service.listAccessContexts(new AccessContextTicketQuery(null, invoking.accessToken(),
				ClientSurface.PLATFORM));
		assertThat(contexts).extracting(AccessContextOption::membershipId)
				.containsExactly("membership-1", "membership-2");
		var switched = fixture.service.selectAccessContext(new SelectAccessContextCommand(null, invoking.accessToken(),
				ClientSurface.PLATFORM, "membership-2"));

		assertThat(switched.membershipId()).isEqualTo("membership-2");
		assertThat(invoking.session().isActive(NOW)).isFalse();
		assertThat(unrelated.session().isActive(NOW)).isTrue();
		assertThat(fixture.sessions.revokedFamilies).containsExactly(invoking.session().refreshTokenFamilyId());
		assertThat(fixture.sessions.started).hasSize(3);
		assertThatThrownBy(() -> fixture.service.listAccessContexts(new AccessContextTicketQuery(null,
				invoking.accessToken(), ClientSurface.PLATFORM)))
				.isInstanceOf(com.nexa.api.tenantaccessgovernance.iam.application.exception.SessionNotFoundException.class);
	}

	@Test
	void selectedUnavailableContextDoesNotRevokeBearerSession() {
		Fixture fixture = twoContextFixture();
		SessionRecord invoking = fixture.startSession(ClientSurface.PLATFORM, "membership-1");
		fixture.policies.removeMembership("membership-2");

		assertThatThrownBy(() -> fixture.service.selectAccessContext(new SelectAccessContextCommand(null,
				invoking.accessToken(), ClientSurface.PLATFORM, "membership-2")))
				.isInstanceOf(SelectedAccessContextUnavailableException.class);
		assertThat(invoking.session().isActive(NOW)).isTrue();
		assertThat(fixture.sessions.revokedFamilies).isEmpty();
		assertThat(fixture.sessions.started).hasSize(1);
	}

	@Test
	void contextAuthorityModelsRequireExactlyOneRedactedCredential() {
		assertThatThrownBy(() -> new AccessContextTicketQuery("ticket", "access", ClientSurface.PLATFORM))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AccessContextTicketQuery(null, null, ClientSurface.PLATFORM))
				.isInstanceOf(IllegalArgumentException.class);
		var query = new AccessContextTicketQuery("secret-ticket", null, ClientSurface.PLATFORM);
		assertThat(query.toString()).doesNotContain("secret-ticket");
		var command = new SelectAccessContextCommand(null, "secret-access-token", ClientSurface.PLATFORM, "membership-1");
		assertThat(command.toString()).doesNotContain("secret-access-token");
	}

	@Test
	void ticketCannotBeUsedForAnotherSurfaceOrAfterExpiryOrIdentitySuspension() {
		Fixture surfaceFixture = twoContextFixture();
		IdentitySignInResult surfaceTicket = surfaceFixture.service.identitySignIn(surfaceFixture.command());
		assertThatThrownBy(() -> surfaceFixture.service.listAccessContexts(
				new com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketQuery(
						surfaceTicket.accessContextTicket(), ClientSurface.PORTAL)))
				.isInstanceOf(InvalidAccessContextTicketException.class);

		Fixture expiryFixture = twoContextFixture();
		IdentitySignInResult expiring = expiryFixture.service.identitySignIn(expiryFixture.command());
		expiryFixture.clock.set(NOW.plusSeconds(301));
		assertThatThrownBy(() -> expiryFixture.service.selectAccessContext(new SelectAccessContextCommand(
				expiring.accessContextTicket(), ClientSurface.PLATFORM, "membership-1")))
				.isInstanceOf(InvalidAccessContextTicketException.class);
		assertThat(expiryFixture.sessions.started).isEmpty();

		Fixture identityFixture = twoContextFixture();
		IdentitySignInResult identityTicket = identityFixture.service.identitySignIn(identityFixture.command());
		identityFixture.identity.suspend();
		assertThatThrownBy(() -> identityFixture.service.listAccessContexts(
				new com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketQuery(
						identityTicket.accessContextTicket(), ClientSurface.PLATFORM)))
				.isInstanceOf(InvalidAccessContextTicketException.class);
	}

	private static Fixture twoContextFixture() {
		Fixture fixture = new Fixture();
		fixture.policies.setOptions(policy(ClientSurface.PLATFORM, "membership-1"),
				policy(ClientSurface.PLATFORM, "membership-2"));
		return fixture;
	}

	private static AccessPolicy policy(ClientSurface surface, String membershipId) {
		return new AccessPolicy(surface, Set.of("operator"), Set.of("catalog:read"), "tenant-1", "icisa", "workspace-" + membershipId,
				"sales-" + membershipId, membershipId, "Carlos", "es", 1, Set.of("role-1"), "ICISA", "Sales");
	}

	private static final class Fixture {
		private final MutableClock clock = new MutableClock(NOW);
		private final UserAccount identity = UserAccount.create(new UserAccountId("user-1"), new Username("carlos"),
				new EmailAddress("carlos@example.test"), new DisplayName("Carlos"));
		private final FakeAccessPolicies policies = new FakeAccessPolicies();
		private final FakeSessions sessions = new FakeSessions();
		private final FakeTickets tickets = new FakeTickets();
		private final FakeDiscoveryScope discoveryScope = new FakeDiscoveryScope();
		private final FakeOpaqueTokens opaque = new FakeOpaqueTokens();
		private final FakeAudit audit = new FakeAudit();
		private final UserAccountQueryPort users = new UserAccountQueryPort() {
			@Override public Optional<StoredUserAccount> findByLogin(com.nexa.api.tenantaccessgovernance.iam.application.model.LoginIdentifier login) {
				return Optional.of(new StoredUserAccount(identity, "encoded"));
			}
			@Override public Optional<UserAccount> findById(UserAccountId id) { return id.equals(identity.id()) ? Optional.of(identity) : Optional.empty(); }
		};
		private final CredentialAuthenticationService credentials = new CredentialAuthenticationService(users,
				(raw, encoded) -> "correct".equals(raw) && "encoded".equals(encoded), new com.nexa.api.tenantaccessgovernance.iam.application.port.out.NoopAuthenticationThrottle(), audit);
		private final FakeAuthenticationTokens authenticationTokens = new FakeAuthenticationTokens();
		private final AccessContextService service = new AccessContextService(credentials, users, policies, authenticationTokens,
				sessions, tickets, discoveryScope, opaque, audit, clock);

		private IdentitySignInCommand command() { return new IdentitySignInCommand("carlos", "correct", ClientSurface.PLATFORM, "client"); }

		private SessionRecord startUnrelatedSession() {
			return startSession(ClientSurface.PORTAL, "membership-unrelated");
		}

		private SessionRecord startSession(ClientSurface surface, String membershipId) {
			var policy = policy(surface, membershipId);
			var subject = new AuthenticationSubject(identity.id(), identity.email(), surface, policy);
			var sessionId = SessionId.random();
			var issued = authenticationTokens.issue(subject, clock.instant(), sessionId);
			var session = AuthenticationSession.start(sessionId, identity.id(), surface,
					RefreshTokenFamilyId.random(), issued.issuedAt(), issued.refreshTokenExpiresAt());
			return sessions.start(session, subject, issued);
		}
	}

	private static final class MutableClock extends Clock {
		private Instant now;
		private MutableClock(Instant now) { this.now = now; }
		private void set(Instant value) { now = value; }
		@Override public ZoneId getZone() { return ZoneId.of("UTC"); }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}

	private static final class FakeAccessPolicies implements AccessPolicyPort {
		private final Map<String, AccessPolicy> byMembership = new HashMap<>();
		private void setOptions(AccessPolicy... options) { byMembership.clear(); for (var option : options) byMembership.put(option.membershipId(), option); }
		private void removeMembership(String membershipId) { byMembership.remove(membershipId); }
		@Override public Optional<AccessPolicy> findFor(UserAccountId id, ClientSurface surface) { return Optional.empty(); }
		@Override public List<AccessPolicy> findAllFor(UserAccountId id, ClientSurface surface) {
			return byMembership.values().stream().filter(policy -> policy.surface() == surface).toList();
		}
		@Override public Optional<AccessPolicy> findForMembership(UserAccountId id, String membershipId, ClientSurface surface) {
			return Optional.ofNullable(byMembership.get(membershipId)).filter(policy -> policy.surface() == surface);
		}
	}

	private static final class FakeAuthenticationTokens implements AuthenticationTokenPort {
		private int sequence;
		@Override public IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt) {
			return new IssuedAuthenticationTokens("access-" + ++sequence, "refresh-" + sequence, issuedAt,
					issuedAt.plusSeconds(30), issuedAt.plusSeconds(300));
		}
	}

	private static final class FakeSessions implements SessionPort {
		private final List<SessionRecord> started = new ArrayList<>();
		private final List<RefreshTokenFamilyId> revokedFamilies = new ArrayList<>();
		@Override public SessionRecord start(AuthenticationSession session, AuthenticationSubject subject, IssuedAuthenticationTokens tokens) {
			SessionRecord record = new SessionRecord(session, subject, tokens);
			started.add(record);
			return record;
		}
		@Override public Optional<SessionRecord> findByAccessToken(String accessToken) {
			return started.stream().filter(record -> record.accessToken().equals(accessToken)).findFirst();
		}
		@Override public Optional<SessionRecord> findByAccessTokenForUpdate(String accessToken) { return findByAccessToken(accessToken); }
		@Override public Optional<SessionRecord> findByRefreshToken(String refreshToken) { return Optional.empty(); }
		@Override public com.nexa.api.tenantaccessgovernance.iam.application.model.RefreshRotation rotateRefreshToken(String token, SessionRecord replacement, Instant rotatedAt) {
			return com.nexa.api.tenantaccessgovernance.iam.application.model.RefreshRotation.invalid();
		}
		@Override public void revoke(SessionId sessionId, Instant revokedAt) { }
		@Override public void revokeFamily(RefreshTokenFamilyId familyId, Instant revokedAt) {
			revokedFamilies.add(familyId);
			started.stream().filter(record -> record.session().refreshTokenFamilyId().equals(familyId))
					.forEach(record -> record.session().revoke(revokedAt));
		}
		@Override public OptionalLong findAuthorizationVersion(SessionId sessionId) { return OptionalLong.empty(); }
	}

	private static final class FakeDiscoveryScope implements AccessContextDiscoveryScopePort {
		private final List<UserAccountId> boundIdentities = new ArrayList<>();
		@Override public void bindTrustedIdentity(UserAccountId userAccountId) { boundIdentities.add(userAccountId); }
	}

	private static final class FakeTickets implements AccessContextTicketPersistencePort {
		private final Map<String, AccessContextTicketRecord> records = new HashMap<>();
		@Override public void create(AccessContextTicketRecord record) { records.put(record.ticketHash(), record); }
		@Override public Optional<AccessContextTicketRecord> findByHash(String hash) { return Optional.ofNullable(records.get(hash)); }
		@Override public Optional<AccessContextTicketRecord> findByHashForUpdate(String hash) { return findByHash(hash); }
		@Override public boolean consume(String hash, Instant consumedAt) {
			AccessContextTicketRecord record = records.get(hash);
			if (record == null || !record.isUsableAt(consumedAt)) return false;
			records.put(hash, new AccessContextTicketRecord(hash, record.userAccountId(), record.surface(),
					record.issuedAt(), record.expiresAt(), consumedAt));
			return true;
		}
		@Override public int invalidatePendingForUser(UserAccountId userAccountId, Instant revokedAt) {
			int invalidated = 0;
			for (var entry : List.copyOf(records.entrySet())) {
				AccessContextTicketRecord record = entry.getValue();
				if (record.userAccountId().equals(userAccountId) && record.isUsableAt(revokedAt)) {
					records.put(entry.getKey(), new AccessContextTicketRecord(record.ticketHash(), record.userAccountId(),
						record.surface(), record.issuedAt(), record.expiresAt(), record.consumedAt(), revokedAt));
					invalidated++;
				}
			}
			return invalidated;
		}
	}

	private static final class FakeOpaqueTokens implements OpaqueSecurityTokenPort {
		@Override public String generate() { return "opaque-selection-ticket"; }
		@Override public String sha256(String value) {
			try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
			catch (Exception exception) { throw new IllegalStateException(exception); }
		}
	}

	private static final class FakeAudit implements SecurityAuditPort {
		private final List<SecurityAuditPort.Event> events = new ArrayList<>();
		@Override public void append(Event event) { events.add(event); }
	}
}
