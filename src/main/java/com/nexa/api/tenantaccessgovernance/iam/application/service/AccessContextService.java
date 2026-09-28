package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.InvalidAccessContextTicketException;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.NoWorkContextException;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.SelectedAccessContextUnavailableException;
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
	private final OpaqueSecurityTokenPort opaqueTokens;
	private final SecurityAuditPort audit;
	private final Clock clock;

	public AccessContextService(CredentialAuthenticationService credentials, UserAccountQueryPort users,
			AccessPolicyPort accessPolicies, AuthenticationTokenPort tokenIssuer, SessionPort sessions,
			AccessContextTicketPersistencePort tickets, OpaqueSecurityTokenPort opaqueTokens,
			SecurityAuditPort audit, Clock clock) {
		this.credentials = Objects.requireNonNull(credentials, "Credential authentication service is required");
		this.users = Objects.requireNonNull(users, "User account query port is required");
		this.accessPolicies = Objects.requireNonNull(accessPolicies, "Access policy port is required");
		this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "Authentication token port is required");
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.tickets = Objects.requireNonNull(tickets, "Access context ticket persistence port is required");
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
		List<AccessPolicy> eligible = accessPolicies.findAllFor(identity.account().id(), command.surface());
		if (eligible.isEmpty()) {
			auditIdentity("IDENTITY_AUTHENTICATED_NO_WORK_CONTEXT", identity.account().id(), command.surface(), now,
					Map.of("contextCount", 0));
			throw new NoWorkContextException();
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
		AccessContextTicketRecord ticket = usableTicket(query.ticket(), query.surface(), now, false);
		activeIdentity(ticket.userAccountId());
		return accessPolicies.findAllFor(ticket.userAccountId(), ticket.surface()).stream()
				.map(AccessContextService::toOption)
				.toList();
	}

	public AuthenticationResult selectAccessContext(SelectAccessContextCommand command) {
		Objects.requireNonNull(command, "Access context selection command is required");
		Instant now = clock.instant();
		String hash = opaqueTokens.sha256(command.ticket());
		AccessContextTicketRecord ticket = tickets.findByHashForUpdate(hash)
				.filter(value -> value.isUsableAt(now) && value.surface() == command.surface())
				.orElseThrow(InvalidAccessContextTicketException::new);
		UserAccount identity = activeIdentity(ticket.userAccountId());
		AccessPolicy policy = accessPolicies.findForMembership(ticket.userAccountId(), command.membershipId(), ticket.surface())
				.orElseThrow(SelectedAccessContextUnavailableException::new);
		AuthenticationResult result = establishSession(identity, ticket.surface(), policy, now);
		if (!tickets.consume(hash, now)) throw new InvalidAccessContextTicketException();
		auditSubject("LOGIN_SUCCEEDED", new AuthenticationSubject(identity.id(), identity.email(), ticket.surface(), policy),
				now, Map.of("flow", "access-context-selection"));
		return result;
	}

	private AccessContextTicketRecord usableTicket(String rawTicket, ClientSurface surface, Instant now, boolean lock) {
		String hash = opaqueTokens.sha256(rawTicket);
		var ticket = lock ? tickets.findByHashForUpdate(hash) : tickets.findByHash(hash);
		return ticket.filter(value -> value.isUsableAt(now) && value.surface() == surface)
				.orElseThrow(InvalidAccessContextTicketException::new);
	}

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
