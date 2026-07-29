package com.nexa.api.iam.application.model;

public record RefreshSessionCommand(String refreshToken) {
	public RefreshSessionCommand {
		if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("Refresh token is required");
		refreshToken = refreshToken.trim();
	}
}
