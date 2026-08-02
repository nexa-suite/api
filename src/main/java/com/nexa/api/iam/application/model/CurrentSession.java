package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.AuthenticationSessionStatus;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

import java.time.Instant;
import java.util.Set;

public record CurrentSession(SessionId sessionId, UserAccountId userAccountId, EmailAddress email,
		ClientSurface surface, Set<String> roles, Set<String> permissions, AuthenticationSessionStatus status,
		Instant createdAt, Instant expiresAt, String displayName, String preferredLanguage,
		String tenantId, String tenantSlug, String workspaceId, String workspaceSlug, String membershipId) {
	public static CurrentSession from(SessionRecord record) {
		var policy = record.subject().policy();
		return new CurrentSession(record.session().id(), record.subject().userAccountId(), record.subject().email(),
			record.subject().surface(), policy.roles(), policy.permissions(), record.session().status(),
			record.session().createdAt(), record.session().expiresAt(), policy.displayName(), policy.preferredLanguage(),
			policy.tenantId(), policy.tenantSlug(), policy.workspaceId(), policy.workspaceSlug(), policy.membershipId());
	}
}
