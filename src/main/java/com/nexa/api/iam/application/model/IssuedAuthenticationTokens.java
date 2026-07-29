package com.nexa.api.iam.application.model;

import java.time.Instant;
import java.util.Objects;

public record IssuedAuthenticationTokens(String accessToken, String refreshToken, Instant issuedAt,
		Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt) {
	public IssuedAuthenticationTokens {
		if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("Access token is required");
		if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("Refresh token is required");
		Objects.requireNonNull(issuedAt, "Token issue time is required");
		Objects.requireNonNull(accessTokenExpiresAt, "Access token expiration is required");
		Objects.requireNonNull(refreshTokenExpiresAt, "Refresh token expiration is required");
		if (!accessTokenExpiresAt.isAfter(issuedAt) || !refreshTokenExpiresAt.isAfter(issuedAt)) {
			throw new IllegalArgumentException("Tokens must expire after issue time");
		}
		accessToken = accessToken.trim();
		refreshToken = refreshToken.trim();
	}
}
