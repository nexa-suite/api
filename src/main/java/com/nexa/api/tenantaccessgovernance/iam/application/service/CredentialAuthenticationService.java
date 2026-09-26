package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.AuthenticationThrottledException;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.InvalidCredentialsException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.LoginIdentifier;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SignInCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.StoredUserAccount;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AuthenticationThrottlePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.NoopAuthenticationThrottle;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserAccountQueryPort;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Shared identity credential checks for workspace-compatible and identity-first sign-in. */
public final class CredentialAuthenticationService {
	private final UserAccountQueryPort users;
	private final PasswordVerificationPort passwords;
	private final AuthenticationThrottlePort throttle;
	private final SecurityAuditPort audit;

	public CredentialAuthenticationService(UserAccountQueryPort users, PasswordVerificationPort passwords,
			AuthenticationThrottlePort throttle, SecurityAuditPort audit) {
		this.users = Objects.requireNonNull(users, "User account query port is required");
		this.passwords = Objects.requireNonNull(passwords, "Password verification port is required");
		this.throttle = Objects.requireNonNull(throttle, "Authentication throttle is required");
		this.audit = Objects.requireNonNull(audit, "Security audit port is required");
	}

	public CredentialAuthenticationService(UserAccountQueryPort users, PasswordVerificationPort passwords) {
		this(users, passwords, new NoopAuthenticationThrottle(), event -> { });
	}

	public StoredUserAccount authenticate(SignInCommand command, Instant now) {
		Objects.requireNonNull(command, "Sign-in command is required");
		if (throttle.isThrottled(command.login(), command.clientFingerprint(), now)) {
			auditAnonymous("AUTHENTICATION_THROTTLED", command, now);
			throw new AuthenticationThrottledException();
		}
		StoredUserAccount stored = users.findByLogin(command.login()).orElse(null);
		if (stored == null || !stored.account().canAuthenticate()
				|| !passwords.matches(command.password(), stored.passwordHash())) {
			reject(command, now);
			throw new InvalidCredentialsException();
		}
		return stored;
	}

	public void reject(SignInCommand command, Instant now) {
		if (throttle.recordFailureAndCheck(command.login(), command.clientFingerprint(), now)) {
			auditAnonymous("AUTHENTICATION_THROTTLED", command, now);
			throw new AuthenticationThrottledException();
		}
		auditAnonymous("LOGIN_FAILED", command, now);
	}

	public void accepted(SignInCommand command) {
		throttle.clear(command.login(), command.clientFingerprint());
	}

	private void auditAnonymous(String type, SignInCommand command, Instant occurredAt) {
		audit.append(new SecurityAuditPort.Event(type, null, null, null, null, command.surface().name(),
				"unknown", "unknown", occurredAt, Map.of("accountResponse", "generic")));
	}
}
