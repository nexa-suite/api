package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.exception.InvalidCredentialsException;
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
	private final Clock clock;

	public SignInService(UserAccountQueryPort userAccounts, PasswordVerificationPort passwordVerifier,
			AccessPolicyPort accessPolicies, AuthenticationTokenPort tokenIssuer, SessionPort sessions, Clock clock) {
		this.userAccounts = Objects.requireNonNull(userAccounts, "User account query port is required");
		this.passwordVerifier = Objects.requireNonNull(passwordVerifier, "Password verification port is required");
		this.accessPolicies = Objects.requireNonNull(accessPolicies, "Access policy port is required");
		this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "Authentication token port is required");
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public AuthenticationResult signIn(SignInCommand command) {
		Objects.requireNonNull(command, "Sign-in command is required");
		StoredUserAccount stored = userAccounts.findByLogin(command.login()).orElseThrow(InvalidCredentialsException::new);
		if (!stored.account().canAuthenticate() || !passwordVerifier.matches(command.password(), stored.passwordHash())) {
			throw new InvalidCredentialsException();
		}
		AccessPolicy policy = accessPolicies.findFor(stored.account().id(), command.surface())
				.orElseThrow(InvalidCredentialsException::new);
		AuthenticationSubject subject = new AuthenticationSubject(stored.account().id(), stored.account().email(),
				command.surface(), policy);
		Instant now = clock.instant();
		IssuedAuthenticationTokens tokens = tokenIssuer.issue(subject, now);
		AuthenticationSession session = AuthenticationSession.start(SessionId.random(), stored.account().id(), command.surface(),
				RefreshTokenFamilyId.random(), tokens.issuedAt(), tokens.refreshTokenExpiresAt());
		SessionRecord record = sessions.start(session, subject, tokens);
		return AuthenticationResult.from(Objects.requireNonNull(record, "Started session is required"));
	}
}
