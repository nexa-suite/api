package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.InvalidAccessContextTicketException;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.SelectedAccessContextUnavailableException;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.SessionNotFoundException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextOption;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketQuery;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketRecord;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessPolicy;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationResult;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationSubject;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IdentitySignInCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IdentitySignInResult;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SelectAccessContextCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SessionRecord;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SignInCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.StoredUserAccount;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextTicketPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextDiscoveryScopePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.OpaqueSecurityTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SessionPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserAccountQueryPort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.SessionId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccount;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Identity-first sign-in and one-use, pre-context selection lifecycle. */
public final class AccessContextService {
	private static final Duration TICKET_TTL = Duration.ofMinutes(5);
	private final CredentialAuthenticationService credentials;
	private final UserAccountQueryPort users;
	private final AccessPolicyPort accessPolicies;
	private final AuthenticationTokenPort tokenIssuer;
	private final SessionPort sessions;
	private final AccessContextTicketPersistencePort tickets;
	private final AccessContextDiscoveryScopePort discoveryScope;
	private final OpaqueSecurityTokenPort opaqueTokens;
	private final SecurityAuditPort audit;
	private final Clock clock;

	public AccessContextService(CredentialAuthenticationService credentials, UserAccountQueryPort users,
			AccessPolicyPort accessPolicies, AuthenticationTokenPort tokenIssuer, SessionPort sessions,
			AccessContextTicketPersistencePort tickets, AccessContextDiscoveryScopePort discoveryScope,
			OpaqueSecurityTokenPort opaqueTokens,
			SecurityAuditPort audit, Clock clock) {
		this.credentials = Objects.requireNonNull(credentials, "Credential authentication service is required");
		this.users = Objects.requireNonNull(users, "User account query port is required");
		this.accessPolicies = Objects.requireNonNull(accessPolicies, "Access policy port is required");
		this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "Authentication token port is required");
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.tickets = Objects.requireNonNull(tickets, "Access context ticket persistence port is required");
		this.discoveryScope = Objects.requireNonNull(discoveryScope, "Access context discovery scope is required");
		this.opaqueTokens = Objects.requireNonNull(opaqueTokens, "Opaque token port is required");
		this.audit = Objects.requireNonNull(audit, "Security audit port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	public IdentitySignInResult identitySignIn(IdentitySignInCommand command) {
		Objects.requireNonNull(command, "Identity sign-in command is required");
		Instant now = clock.instant();
		SignInCommand credentialCommand = new SignInCommand(command.login(), command.password(), null,
				command.surface(), command.clientFingerprint());
		StoredUserAccount identity = credentials.authenticate(credentialCommand, now);
		credentials.accepted(credentialCommand);
		discoveryScope.bindTrustedIdentity(identity.account().id());
		List<AccessPolicy> eligible = accessPolicies.findAllFor(identity.account().id(), command.surface());
		if (eligible.isEmpty()) {
			auditIdentity("IDENTITY_AUTHENTICATED_NO_WORK_CONTEXT", identity.account().id(), command.surface(), now,
					Map.of("contextCount", 0));
			return IdentitySignInResult.noWorkContext();
		}
		if (eligible.size() == 1) {
			AuthenticationResult result = establishSession(identity.account(), command.surface(), eligible.getFirst(), now);
			auditSubject("LOGIN_SUCCEEDED", new AuthenticationSubject(identity.account().id(), identity.account().email(),
					command.surface(), eligible.getFirst()), now, Map.of("flow", "identity-first"));
			return IdentitySignInResult.authenticated(result);
		}

		String ticket = opaqueTokens.generate();
		Instant expiresAt = now.plus(TICKET_TTL);
		String ticketHash = opaqueTokens.sha256(ticket);
		tickets.create(new AccessContextTicketRecord(ticketHash, identity.account().id(), command.surface(), now, expiresAt, null));
		auditIdentity("IDENTITY_AUTHENTICATED_CONTEXT_SELECTION_REQUIRED", identity.account().id(), command.surface(), now,
				Map.of("contextCount", eligible.size()));
		return IdentitySignInResult.selectionRequired(ticket, expiresAt);
	}

	public List<AccessContextOption> listAccessContexts(AccessContextTicketQuery query) {
		Objects.requireNonNull(query, "Access context ticket query is required");
		Instant now = clock.instant();
		UserAccountId userAccountId;
		if (hasTicket(query)) {
			AccessContextTicketRecord ticket = usableTicket(query.ticket(), query.surface(), now, false);
			userAccountId = activeIdentity(ticket.userAccountId()).id();
		} else {
			SessionRecord session = usableSession(query.accessToken(), query.surface(), now, false);
			userAccountId = activeIdentity(session.session().userAccountId()).id();
		}
		discoveryScope.bindTrustedIdentity(userAccountId);
		return accessPolicies.findAllFor(userAccountId, query.surface()).stream()
				.map(AccessContextService::toOption)
				.toList();
	}

	public AuthenticationResult selectAccessContext(SelectAccessContextCommand command) {
		Objects.requireNonNull(command, "Access context selection command is required");
		Instant now = clock.instant();
		if (hasTicket(command)) {
			String hash = opaqueTokens.sha256(command.ticket());
			AccessContextTicketRecord ticket = tickets.findByHashForUpdate(hash)
					.filter(value -> value.isUsableAt(now) && value.surface() == command.surface())
					.orElseThrow(InvalidAccessContextTicketException::new);
			UserAccount identity = activeIdentity(ticket.userAccountId());
			discoveryScope.bindTrustedIdentity(identity.id());
			AccessPolicy policy = selectedPolicy(ticket.userAccountId(), command.membershipId(), ticket.surface());
			AuthenticationResult result = establishSession(identity, ticket.surface(), policy, now);
			if (!tickets.consume(hash, now)) throw new InvalidAccessContextTicketException();
			auditSubject("LOGIN_SUCCEEDED", new AuthenticationSubject(identity.id(), identity.email(), ticket.surface(), policy),
					now, Map.of("flow", "access-context-selection", "authority", "pre-context-ticket"));
			return result;
		}

		SessionRecord invokingSession = usableSession(command.accessToken(), command.surface(), now, true);
		UserAccount identity = activeIdentity(invokingSession.session().userAccountId());
		discoveryScope.bindTrustedIdentity(identity.id());
		AccessPolicy policy = selectedPolicy(identity.id(), command.membershipId(), command.surface());
		AuthenticationResult result = establishSession(identity, command.surface(), policy, now);
		sessions.revokeFamily(invokingSession.session().refreshTokenFamilyId(), now);
		auditSubject("ACCESS_CONTEXT_SWITCHED", new AuthenticationSubject(identity.id(), identity.email(), command.surface(), policy),
				now, Map.of("flow", "access-context-selection", "previousMembershipId",
					invokingSession.subject().policy().membershipId()));
		return result;
	}

	private AccessContextTicketRecord usableTicket(String rawTicket, ClientSurface surface, Instant now, boolean lock) {
		String hash = opaqueTokens.sha256(rawTicket);
		var ticket = lock ? tickets.findByHashForUpdate(hash) : tickets.findByHash(hash);
		return ticket.filter(value -> value.isUsableAt(now) && value.surface() == surface)
				.orElseThrow(InvalidAccessContextTicketException::new);
	}

	private SessionRecord usableSession(String accessToken, ClientSurface surface, Instant now, boolean lock) {
		var record = (lock ? sessions.findByAccessTokenForUpdate(accessToken) : sessions.findByAccessToken(accessToken))
				.filter(value -> value.session().surface() == surface
						&& value.session().isActive(now)
						&& value.tokens().accessTokenExpiresAt().isAfter(now)
						&& !sessions.isFamilyRevoked(value.session().refreshTokenFamilyId()))
				.orElseThrow(SessionNotFoundException::new);
		return record;
	}

	private AccessPolicy selectedPolicy(UserAccountId userAccountId, String membershipId, ClientSurface surface) {
		return accessPolicies.findForMembership(userAccountId, membershipId, surface)
				.orElseThrow(SelectedAccessContextUnavailableException::new);
	}

	private static boolean hasTicket(AccessContextTicketQuery query) { return query.ticket() != null && !query.ticket().isBlank(); }
	private static boolean hasTicket(SelectAccessContextCommand command) { return command.ticket() != null && !command.ticket().isBlank(); }

	private UserAccount activeIdentity(UserAccountId id) {
		return users.findById(id).filter(UserAccount::canAuthenticate)
				.orElseThrow(InvalidAccessContextTicketException::new);
	}

	private AuthenticationResult establishSession(UserAccount identity, ClientSurface surface, AccessPolicy policy, Instant now) {
		AuthenticationSubject subject = new AuthenticationSubject(identity.id(), identity.email(), surface, policy);
		SessionId sessionId = SessionId.random();
		IssuedAuthenticationTokens tokens = tokenIssuer.issue(subject, now, sessionId);
		AuthenticationSession session = AuthenticationSession.start(sessionId, identity.id(), surface,
				RefreshTokenFamilyId.random(), tokens.issuedAt(), tokens.refreshTokenExpiresAt());
		SessionRecord record = sessions.start(session, subject, tokens);
		return AuthenticationResult.from(Objects.requireNonNull(record, "Started session is required"));
	}

	private void auditIdentity(String type, UserAccountId userAccountId, ClientSurface surface, Instant occurredAt,
			Map<String, Object> metadata) {
		audit.append(new SecurityAuditPort.Event(type, uuid(userAccountId.value()), null, null, null,
				surface.name(), "unknown", "unknown", occurredAt, metadata));
	}

	private void auditSubject(String type, AuthenticationSubject subject, Instant occurredAt, Map<String, Object> metadata) {
		AccessPolicy policy = subject.policy();
		audit.append(new SecurityAuditPort.Event(type, uuid(subject.userAccountId().value()), null,
				uuid(policy.tenantId()), uuid(policy.workspaceId()), subject.surface().name(), "unknown", "unknown",
				occurredAt, metadata));
	}

	private static AccessContextOption toOption(AccessPolicy policy) {
		return new AccessContextOption(policy.membershipId(), policy.tenantId(), policy.tenantName(), policy.tenantSlug(),
				policy.workspaceId(), policy.workspaceName(), policy.workspaceSlug());
	}

	private static UUID uuid(String value) {
		try { return value == null ? null : UUID.fromString(value); }
		catch (IllegalArgumentException ignored) { return null; }
	}
}
