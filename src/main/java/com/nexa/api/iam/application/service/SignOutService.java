package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.SignOutCommand;
import com.nexa.api.iam.application.port.in.SignOutUseCase;
import com.nexa.api.iam.application.port.out.SessionPort;

import java.time.Clock;
import java.util.Objects;

public final class SignOutService implements SignOutUseCase {
	private final SessionPort sessions;
	private final Clock clock;

	public SignOutService(SessionPort sessions, Clock clock) {
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public void signOut(SignOutCommand command) {
		Objects.requireNonNull(command, "Sign-out command is required");
		sessions.findByAccessToken(command.accessToken()).ifPresent(record -> sessions.revoke(record.session().id(), clock.instant()));
	}
}
