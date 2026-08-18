package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.exception.InvalidCredentialsException;
import com.nexa.api.iam.application.exception.AuthenticationThrottledException;
import com.nexa.api.iam.application.model.AccessPolicy;
import com.nexa.api.iam.application.model.AuthenticationResult;
import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.application.model.SessionRecord;
import com.nexa.api.iam.application.model.SignInCommand;
import com.nexa.api.iam.application.model.StoredUserAccount;
import com.nexa.api.iam.application.port.in.SignInUseCase;
import com.nexa.api.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.iam.application.port.out.SessionPort;
import com.nexa.api.iam.application.port.out.UserAccountQueryPort;
import com.nexa.api.iam.application.port.out.AuthenticationThrottlePort;
import com.nexa.api.iam.application.port.out.NoopAuthenticationThrottle;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.iam.domain.model.session.SessionId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class SignInService implements SignInUseCase {
	private final UserAccountQueryPort userAccounts;
	private final PasswordVerificationPort passwordVerifier;
	private final AccessPolicyPort accessPolicies;
	private final AuthenticationTokenPort tokenIssuer;
	private final SessionPort sessions;
	private final AuthenticationThrottlePort throttle;
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
		this.userAccounts = Objects.requireNonNull(userAccounts, "User account query port is required");
		this.passwordVerifier = Objects.requireNonNull(passwordVerifier, "Password verification port is required");
		this.accessPolicies = Objects.requireNonNull(accessPolicies, "Access policy port is required");
		this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "Authentication token port is required");
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.throttle = Objects.requireNonNull(throttle, "Authentication throttle is required");
		this.audit = Objects.requireNonNull(audit, "Security audit port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public AuthenticationResult signIn(SignInCommand command) {
		Objects.requireNonNull(command, "Sign-in command is required");
		Instant now = clock.instant();
		if (throttle.isThrottled(command.login(), command.clientFingerprint(), now)) {
			auditAnonymous("AUTHENTICATION_THROTTLED", command, now); throw new AuthenticationThrottledException();
		}
		StoredUserAccount stored = userAccounts.findByLogin(command.login()).orElse(null);
		if (stored == null) {
			if (throttle.recordFailureAndCheck(command.login(), command.clientFingerprint(), now)) { auditAnonymous("AUTHENTICATION_THROTTLED", command, now); throw new AuthenticationThrottledException(); }
			auditAnonymous("LOGIN_FAILED", command, now);
			throw new InvalidCredentialsException();
		}
		if (!stored.account().canAuthenticate() || !passwordVerifier.matches(command.password(), stored.passwordHash())) {
			if (throttle.recordFailureAndCheck(command.login(), command.clientFingerprint(), now)) { auditAnonymous("AUTHENTICATION_THROTTLED", command, now); throw new AuthenticationThrottledException(); }
			auditAnonymous("LOGIN_FAILED", command, now);
			throw new InvalidCredentialsException();
		}
		AccessPolicy policy = accessPolicies.findFor(stored.account().id(), command.workspaceSlug(), command.surface()).orElse(null);
		if (policy == null) {
			if (throttle.recordFailureAndCheck(command.login(), command.clientFingerprint(), now)) { auditAnonymous("AUTHENTICATION_THROTTLED", command, now); throw new AuthenticationThrottledException(); }
			auditAnonymous("LOGIN_FAILED", command, now);
			throw new InvalidCredentialsException();
		}
		throttle.clear(command.login(), command.clientFingerprint());
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

	private void auditAnonymous(String type, SignInCommand command, Instant occurredAt) {
		audit.append(new SecurityAuditPort.Event(type, null, null, null, null, command.surface().name(), "unknown", "unknown", occurredAt,
			java.util.Map.of("accountResponse", "generic")));
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
