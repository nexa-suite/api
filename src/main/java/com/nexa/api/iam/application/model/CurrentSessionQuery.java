package com.nexa.api.iam.application.model;

public record CurrentSessionQuery(String accessToken) {
	public CurrentSessionQuery {
		if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("Access token is required");
		accessToken = accessToken.trim();
	}
}
