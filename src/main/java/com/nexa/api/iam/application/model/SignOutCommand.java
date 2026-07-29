package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

public record SignOutCommand(String accessToken, SessionId sessionId, UserAccountId userId, ClientSurface surface) {
	public SignOutCommand(String accessToken) {
		this(accessToken, null, null, null);
	}

	public SignOutCommand(SessionId sessionId, UserAccountId userId, ClientSurface surface) {
		this(null, sessionId, userId, surface);
	}

	public SignOutCommand {
		if (sessionId == null && (accessToken == null || accessToken.isBlank())) {
			throw new IllegalArgumentException("Session identity is required");
		}
		if (sessionId != null && (userId == null || surface == null)) {
			throw new IllegalArgumentException("Verified session identity is incomplete");
		}
		if (accessToken != null) accessToken = accessToken.trim();
	}

	public boolean hasVerifiedIdentity() {
		return sessionId != null;
	}
}
