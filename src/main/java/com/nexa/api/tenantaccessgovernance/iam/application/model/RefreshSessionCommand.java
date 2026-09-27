package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.publicapi.ClientSurface;

public record RefreshSessionCommand(String refreshToken, ClientSurface surface) {
	public RefreshSessionCommand(String refreshToken) {
		this(refreshToken, null);
	}

	public RefreshSessionCommand {
		if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("Refresh token is required");
		refreshToken = refreshToken.trim();
	}
}
