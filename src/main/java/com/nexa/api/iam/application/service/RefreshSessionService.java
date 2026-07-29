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

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class RefreshSessionService implements RefreshSessionUseCase {
	private final SessionPort sessions;
	private final AccessPolicyPort accessPolicies;
	private final AuthenticationTokenPort tokenIssuer;
	private final Clock clock;

	public RefreshSessionService(SessionPort sessions, AccessPolicyPort accessPolicies,
			AuthenticationTokenPort tokenIssuer, Clock clock) {
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.accessPolicies = Objects.requireNonNull(accessPolicies, "Access policy port is required");
		this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "Authentication token port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public AuthenticationResult refresh(RefreshSessionCommand command) {
		Objects.requireNonNull(command, "Refresh command is required");
		SessionRecord current = sessions.findByRefreshToken(command.refreshToken()).orElseThrow(InvalidRefreshTokenException::new);
		Instant now = clock.instant();
		if (!current.session().isActive(now)) throw new InvalidRefreshTokenException();
		AccessPolicy policy = accessPolicies.findFor(current.subject().userAccountId(), current.subject().surface())
				.orElseThrow(InvalidRefreshTokenException::new);
		AuthenticationSubject subject = new AuthenticationSubject(current.subject().userAccountId(), current.subject().email(),
				current.subject().surface(), policy);
		IssuedAuthenticationTokens tokens = tokenIssuer.issue(subject, now);
		var replacementSession = com.nexa.api.iam.domain.model.session.AuthenticationSession.start(current.session().id(),
				current.session().userAccountId(), current.session().surface(), current.session().refreshTokenFamilyId(),
				current.session().createdAt(), tokens.refreshTokenExpiresAt());
		SessionRecord replacement = new SessionRecord(replacementSession, subject, tokens);
		RefreshRotation rotation = sessions.rotateRefreshToken(command.refreshToken(), replacement, now);
		if (rotation.status() == RefreshRotation.Status.REUSED) {
			sessions.revokeFamily(current.session().refreshTokenFamilyId(), now);
			throw new RefreshTokenReuseDetectedException();
		}
		if (rotation.status() != RefreshRotation.Status.ROTATED) throw new InvalidRefreshTokenException();
		return AuthenticationResult.from(rotation.session());
	}
}
