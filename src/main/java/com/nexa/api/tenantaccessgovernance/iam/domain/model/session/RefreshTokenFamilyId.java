package com.nexa.api.tenantaccessgovernance.iam.domain.model.session;

import java.util.UUID;

public record RefreshTokenFamilyId(String value) {
	public RefreshTokenFamilyId {
		if (value == null || value.isBlank()) throw new SessionInvariantViolation("Refresh token family id is required");
		value = value.trim();
		if (value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
			throw new SessionInvariantViolation("Refresh token family id contains invalid characters");
		}
	}

	public static RefreshTokenFamilyId random() {
		return new RefreshTokenFamilyId(UUID.randomUUID().toString());
	}

	@Override
	public String toString() {
		return value;
	}
}
