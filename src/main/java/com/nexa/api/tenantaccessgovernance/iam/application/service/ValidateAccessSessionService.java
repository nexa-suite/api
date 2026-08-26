package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.SessionNotFoundException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.ValidatedAccessSession;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SessionPort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.SessionId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.time.Clock;
import java.util.Objects;

public final class ValidateAccessSessionService implements ValidateAccessSessionUseCase {
	private final SessionPort sessions;
	private final Clock clock;

	public ValidateAccessSessionService(SessionPort sessions, Clock clock) {
		this.sessions = Objects.requireNonNull(sessions, "Session port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public ValidatedAccessSession validate(SessionId sessionId, UserAccountId userId, ClientSurface surface) {
		var record = sessions.findBySessionId(Objects.requireNonNull(sessionId, "Session id is required"))
				.orElseThrow(SessionNotFoundException::new);
		if (!record.session().userAccountId().equals(userId) || record.session().surface() != surface
				|| !record.session().isActive(clock.instant())
				|| sessions.isFamilyRevoked(record.session().refreshTokenFamilyId())) {
			throw new SessionNotFoundException();
		}
		return new ValidatedAccessSession(record.session(), userId, surface);
	}

	@Override
	public ValidatedAccessSession validate(SessionId sessionId, UserAccountId userId, ClientSurface surface,
			long authorizationVersion) {
		/*
		 * Session lifecycle and membership authorization are separate boundaries.
		 * A disabled membership or a changed role must be revalidated by the
		 * tenant access context and return 403; it must not masquerade as a
		 * revoked session (401). Explicit sign-out/revocation is handled by the
		 * base validation above.
		 */
		return validate(sessionId, userId, surface);
	}
}
