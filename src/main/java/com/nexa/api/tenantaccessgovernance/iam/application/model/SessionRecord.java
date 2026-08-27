package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.AuthenticationSession;

import java.util.Objects;

public record SessionRecord(AuthenticationSession session, AuthenticationSubject subject,
		IssuedAuthenticationTokens tokens) {
	public SessionRecord {
		Objects.requireNonNull(session, "Authentication session is required");
		Objects.requireNonNull(subject, "Authentication subject is required");
		Objects.requireNonNull(tokens, "Authentication tokens are required");
		if (!session.userAccountId().equals(subject.userAccountId())) {
			throw new IllegalArgumentException("Session user does not match subject");
		}
		if (session.surface() != subject.surface()) throw new IllegalArgumentException("Session surface does not match subject");
	}

	public String accessToken() { return tokens.accessToken(); }
	public String refreshToken() { return tokens.refreshToken(); }
}
