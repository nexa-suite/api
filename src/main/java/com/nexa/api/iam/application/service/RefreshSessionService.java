package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.exception.InvalidRefreshTokenException;
import com.nexa.api.iam.application.exception.RefreshTokenReuseDetectedException;
import com.nexa.api.iam.application.model.AccessPolicy;
import com.nexa.api.iam.application.model.AuthenticationResult;
import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.application.model.RefreshRotation;
import com.nexa.api.iam.application.model.RefreshSessionCommand;
import com.nexa.api.iam.application.model.SessionRecord;
import com.nexa.api.iam.application.port.in.RefreshSessionUseCase;
import com.nexa.api.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.iam.application.port.out.SessionPort;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class RefreshSessionService implements RefreshSessionUseCase {
	private final SessionPort sessions;
	private final AccessPolicyPort accessPolicies;
	private final AuthenticationTokenPort tokenIssuer;
	private final Clock clock;
	private final SecurityAuditPort audit;

	public RefreshSessionService(SessionPort sessions, AccessPolicyPort accessPolicies,
			AuthenticationTokenPort tokenIssuer, Clock clock) {
		this(sessions, accessPolicies, tokenIssuer, event -> { }, clock);
	}

	public RefreshSessionService(SessionPort sessions, AccessPolicyPort accessPolicies,
			AuthenticationTokenPort tokenIssuer, SecurityAuditPort audit, Clock clock) {
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.accessPolicies = Objects.requireNonNull(accessPolicies, "Access policy port is required");
		this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "Authentication token port is required");
		this.audit = Objects.requireNonNull(audit, "Security audit port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public AuthenticationResult refresh(RefreshSessionCommand command) {
		Objects.requireNonNull(command, "Refresh command is required");
		SessionRecord current = sessions.findByRefreshToken(command.refreshToken()).orElseThrow(InvalidRefreshTokenException::new);
		Instant now = clock.instant();
		if (current.session().revokedAt() != null) {
			sessions.revokeFamily(current.session().refreshTokenFamilyId(), now);
			throw new RefreshTokenReuseDetectedException();
		}
		if (!current.session().isActive(now)) throw new InvalidRefreshTokenException();
		if (command.surface() != null && command.surface() != current.subject().surface()) throw new InvalidRefreshTokenException();
		AccessPolicy policy = accessPolicies.findFor(current.subject().userAccountId(), current.subject().policy().workspaceSlug(), current.subject().surface())
				.orElseThrow(InvalidRefreshTokenException::new);
		AuthenticationSubject subject = new AuthenticationSubject(current.subject().userAccountId(), current.subject().email(),
				current.subject().surface(), policy);
		var replacementSessionId = com.nexa.api.iam.domain.model.session.SessionId.random();
		IssuedAuthenticationTokens tokens = tokenIssuer.issue(subject, now, replacementSessionId);
		var replacementSession = com.nexa.api.iam.domain.model.session.AuthenticationSession.start(replacementSessionId,
				current.session().userAccountId(), current.session().surface(), current.session().refreshTokenFamilyId(),
				current.session().createdAt(), tokens.refreshTokenExpiresAt());
		SessionRecord replacement = new SessionRecord(replacementSession, subject, tokens);
		RefreshRotation rotation = sessions.rotateRefreshToken(command.refreshToken(), replacement, now);
		if (rotation.status() == RefreshRotation.Status.REUSED) {
			sessions.revokeFamily(current.session().refreshTokenFamilyId(), now);
			throw new RefreshTokenReuseDetectedException();
		}
		if (rotation.status() != RefreshRotation.Status.ROTATED) throw new InvalidRefreshTokenException();
		audit.append(new SecurityAuditPort.Event("LOGIN_SUCCEEDED", uuid(replacement.subject().userAccountId().value()), null, null, null,
				replacement.subject().surface().name(), "unknown", "unknown", now, java.util.Map.of("flow", "refresh")));
		return AuthenticationResult.from(rotation.session());
	}

	private static java.util.UUID uuid(String value) {
		try { return value == null ? null : java.util.UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
	}
}
