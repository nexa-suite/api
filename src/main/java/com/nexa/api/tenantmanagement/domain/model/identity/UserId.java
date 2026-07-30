package com.nexa.api.tenantmanagement.domain.model.identity;

import java.util.UUID;

public record UserId(UUID value) {
	public UserId {
		value = UuidIdentitySupport.required(value, "User id");
	}

	public UserId(String value) {
		this(UuidIdentitySupport.parse(value, "User id"));
	}

	public static UserId random() {
		return new UserId(UUID.randomUUID());
	}

	public static UserId from(String value) {
		return new UserId(value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
