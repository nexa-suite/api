package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.ValidatedAccessSession;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

public interface ValidateAccessSessionUseCase {
	ValidatedAccessSession validate(SessionId sessionId, UserAccountId userId, ClientSurface surface);

	/**
	 * Validates the durable session and, when supported by the adapter, the
	 * authorization snapshot carried by the access token.
	 */
	default ValidatedAccessSession validate(SessionId sessionId, UserAccountId userId, ClientSurface surface,
			long authorizationVersion) {
		if (authorizationVersion < 0) throw new IllegalArgumentException("Authorization version cannot be negative");
		return validate(sessionId, userId, surface);
	}
}
