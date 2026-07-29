package com.nexa.api.iam.application.model;

import com.nexa.api.iam.domain.model.access.ClientSurface;

public record RefreshSessionCommand(String refreshToken, ClientSurface surface) {
	public RefreshSessionCommand(String refreshToken) {
		this(refreshToken, null);
	}

	public RefreshSessionCommand {
		if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("Refresh token is required");
		refreshToken = refreshToken.trim();
	}
}
