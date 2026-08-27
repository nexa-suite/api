package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.SessionNotFoundException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.CurrentSession;
import com.nexa.api.tenantaccessgovernance.iam.application.model.CurrentSessionQuery;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.CurrentSessionUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.GetCurrentSessionUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SessionPort;

import java.time.Clock;
import java.util.Objects;

public final class CurrentSessionService implements CurrentSessionUseCase, GetCurrentSessionUseCase {
	private final SessionPort sessions;
	private final Clock clock;

	public CurrentSessionService(SessionPort sessions, Clock clock) {
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public CurrentSession currentSession(CurrentSessionQuery query) {
		Objects.requireNonNull(query, "Current session query is required");
		var record = sessions.findByAccessToken(query.accessToken()).orElseThrow(SessionNotFoundException::new);
		var now = clock.instant();
		if (!record.session().isActive(now) || !record.tokens().accessTokenExpiresAt().isAfter(now)) {
			throw new SessionNotFoundException();
		}
		return CurrentSession.from(record);
	}
}
