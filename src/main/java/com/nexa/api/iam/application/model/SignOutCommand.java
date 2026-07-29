package com.nexa.api.iam.application.model;

public record SignOutCommand(String accessToken) {
	public SignOutCommand {
		if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("Access token is required");
		accessToken = accessToken.trim();
	}
}
