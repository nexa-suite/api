package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

import java.time.Instant;
import java.util.Set;

public record AuthenticationResult(SessionId sessionId, UserAccountId userAccountId, EmailAddress email,
		ClientSurface surface, String role, Set<String> permissions, String accessToken, String refreshToken,
		Instant issuedAt, Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt,
		String tenantId, String tenantSlug, String workspaceId, String workspaceSlug, String membershipId,
		String displayName, String preferredLanguage) {
	public Set<String> roles() {
		return role == null || role.isBlank() ? Set.of() : java.util.Arrays.stream(role.split(","))
				.map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}
	public AuthenticationResult(SessionId sessionId, UserAccountId userAccountId, EmailAddress email,
			ClientSurface surface, String role, Set<String> permissions, String accessToken, String refreshToken,
			Instant issuedAt, Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt) {
		this(sessionId, userAccountId, email, surface, role, permissions, accessToken, refreshToken, issuedAt,
				accessTokenExpiresAt, refreshTokenExpiresAt, null, null, null, null, null, null, null);
	}

	public static AuthenticationResult from(SessionRecord record) {
		var policy = record.subject().policy();
		return new AuthenticationResult(record.session().id(), record.subject().userAccountId(), record.subject().email(),
			record.subject().surface(), policy.role(), policy.permissions(), record.accessToken(), record.refreshToken(),
			record.tokens().issuedAt(), record.tokens().accessTokenExpiresAt(), record.tokens().refreshTokenExpiresAt(),
			policy.tenantId(), policy.tenantSlug(), policy.workspaceId(), policy.workspaceSlug(), policy.membershipId(),
			policy.displayName(), policy.preferredLanguage());
	}
}
