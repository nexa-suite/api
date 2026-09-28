package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.InvalidCredentialsException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessPolicy;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationResult;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationSubject;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SessionRecord;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SignInCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SignInUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SessionPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserAccountQueryPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AuthenticationThrottlePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.NoopAuthenticationThrottle;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.SessionId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class SignInService implements SignInUseCase {
	private final CredentialAuthenticationService credentials;
	private final AccessPolicyPort accessPolicies;
	private final AuthenticationTokenPort tokenIssuer;
	private final SessionPort sessions;
	private final SecurityAuditPort audit;
	private final Clock clock;

	public SignInService(UserAccountQueryPort userAccounts, PasswordVerificationPort passwordVerifier,
			AccessPolicyPort accessPolicies, AuthenticationTokenPort tokenIssuer, SessionPort sessions, Clock clock) {
		this(userAccounts, passwordVerifier, accessPolicies, tokenIssuer, sessions, new NoopAuthenticationThrottle(), event -> { }, clock);
	}

	public SignInService(UserAccountQueryPort userAccounts, PasswordVerificationPort passwordVerifier,
			AccessPolicyPort accessPolicies, AuthenticationTokenPort tokenIssuer, SessionPort sessions,
			AuthenticationThrottlePort throttle, Clock clock) {
		this(userAccounts, passwordVerifier, accessPolicies, tokenIssuer, sessions, throttle, event -> { }, clock);
	}

	public SignInService(UserAccountQueryPort userAccounts, PasswordVerificationPort passwordVerifier,
			AccessPolicyPort accessPolicies, AuthenticationTokenPort tokenIssuer, SessionPort sessions,
			AuthenticationThrottlePort throttle, SecurityAuditPort audit, Clock clock) {
		this(new CredentialAuthenticationService(userAccounts, passwordVerifier, throttle, audit), accessPolicies,
				tokenIssuer, sessions, audit, clock);
	}

	public SignInService(CredentialAuthenticationService credentials, AccessPolicyPort accessPolicies,
			AuthenticationTokenPort tokenIssuer, SessionPort sessions, SecurityAuditPort audit, Clock clock) {
		this.credentials = Objects.requireNonNull(credentials, "Credential authentication service is required");
		this.accessPolicies = Objects.requireNonNull(accessPolicies, "Access policy port is required");
		this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "Authentication token port is required");
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.audit = Objects.requireNonNull(audit, "Security audit port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public AuthenticationResult signIn(SignInCommand command) {
		Objects.requireNonNull(command, "Sign-in command is required");
		Instant now = clock.instant();
		var stored = credentials.authenticate(command, now);
		AccessPolicy policy = accessPolicies.findFor(stored.account().id(), command.workspaceSlug(), command.surface()).orElse(null);
		if (policy == null) {
			credentials.reject(command, now);
			throw new InvalidCredentialsException();
		}
		credentials.accepted(command);
		AuthenticationSubject subject = new AuthenticationSubject(stored.account().id(), stored.account().email(),
				command.surface(), policy);
		SessionId sessionId = SessionId.random();
		IssuedAuthenticationTokens tokens = tokenIssuer.issue(subject, now, sessionId);
		AuthenticationSession session = AuthenticationSession.start(sessionId, stored.account().id(), command.surface(),
				RefreshTokenFamilyId.random(), tokens.issuedAt(), tokens.refreshTokenExpiresAt());
		SessionRecord record = sessions.start(session, subject, tokens);
		auditSubject("LOGIN_SUCCEEDED", subject, now, java.util.Map.of("roles", policy.roles()));
		return AuthenticationResult.from(Objects.requireNonNull(record, "Started session is required"));
	}

	private void auditSubject(String type, AuthenticationSubject subject, Instant occurredAt, java.util.Map<String, Object> metadata) {
		var policy = subject.policy();
		audit.append(new SecurityAuditPort.Event(type, uuid(subject.userAccountId().value()), null, uuid(policy.tenantId()), uuid(policy.workspaceId()),
			subject.surface().name(), "unknown", "unknown", occurredAt, metadata));
	}

	private static java.util.UUID uuid(String value) {
		try { return value == null ? null : java.util.UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
	}
}
