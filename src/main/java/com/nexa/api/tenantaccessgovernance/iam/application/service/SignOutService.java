package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.model.SignOutCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SignOutUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SessionPort;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;

import java.time.Clock;
import java.util.Objects;

public final class SignOutService implements SignOutUseCase {
	private final SessionPort sessions;
	private final Clock clock;
	private final SecurityAuditPort audit;

	public SignOutService(SessionPort sessions, Clock clock) {
		this(sessions, event -> { }, clock);
	}

	public SignOutService(SessionPort sessions, SecurityAuditPort audit, Clock clock) {
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.audit = Objects.requireNonNull(audit, "Security audit port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public void signOut(SignOutCommand command) {
		Objects.requireNonNull(command, "Sign-out command is required");
		if (command.hasVerifiedIdentity()) {
			sessions.revoke(command.sessionId(), command.userId(), command.surface(), clock.instant());
			audit.append(new SecurityAuditPort.Event("LOGOUT", uuid(command.userId().value()), null, null, null,
				command.surface().name(), "unknown", "unknown", clock.instant(), java.util.Map.of("sessionId", command.sessionId().value().toString())));
			return;
		}
		// Compatibility path for existing application-level callers. HTTP presentation uses verified JWT identity.
		sessions.findByAccessToken(command.accessToken()).ifPresent(record -> {
			sessions.revoke(record.session().id(), record.subject().userAccountId(), record.subject().surface(), clock.instant());
		audit.append(new SecurityAuditPort.Event("LOGOUT", uuid(record.subject().userAccountId().value()), null, null, null,
				record.subject().surface().name(), "unknown", "unknown", clock.instant(), java.util.Map.of("sessionId", record.session().id().value())));
		});
	}

	private static java.util.UUID uuid(String value) {
		try { return value == null ? null : java.util.UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
	}
}
