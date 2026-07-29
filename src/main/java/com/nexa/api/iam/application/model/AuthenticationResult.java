package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

import java.time.Instant;
import java.util.Set;

public record AuthenticationResult(SessionId sessionId, UserAccountId userAccountId, EmailAddress email,
		ClientSurface surface, String role, Set<String> permissions, String accessToken, String refreshToken,
		Instant issuedAt, Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt) {
	public static AuthenticationResult from(SessionRecord record) {
		return new AuthenticationResult(record.session().id(), record.subject().userAccountId(), record.subject().email(),
				record.subject().surface(), record.subject().policy().role(), record.subject().policy().permissions(),
			record.accessToken(), record.refreshToken(), record.tokens().issuedAt(), record.tokens().accessTokenExpiresAt(),
			record.tokens().refreshTokenExpiresAt());
	}
}
