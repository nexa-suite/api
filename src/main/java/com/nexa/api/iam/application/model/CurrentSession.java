package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.AuthenticationSessionStatus;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

import java.time.Instant;
import java.util.Set;

public record CurrentSession(SessionId sessionId, UserAccountId userAccountId, EmailAddress email,
		ClientSurface surface, String role, Set<String> permissions, AuthenticationSessionStatus status,
		Instant createdAt, Instant expiresAt) {
	public static CurrentSession from(SessionRecord record) {
		return new CurrentSession(record.session().id(), record.subject().userAccountId(), record.subject().email(),
			record.subject().surface(), record.subject().policy().role(), record.subject().policy().permissions(),
			record.session().status(), record.session().createdAt(), record.session().expiresAt());
	}
}
