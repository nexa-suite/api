package com.nexa.api.iam.domain.model.session;

import java.util.UUID;

public record SessionId(String value) {
	public SessionId {
		if (value == null || value.isBlank()) throw new SessionInvariantViolation("Session id is required");
		value = value.trim();
		if (value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
			throw new SessionInvariantViolation("Session id contains invalid characters");
		}
	}

	public static SessionId random() {
		return new SessionId(UUID.randomUUID().toString());
	}

	@Override
	public String toString() {
		return value;
	}
}
