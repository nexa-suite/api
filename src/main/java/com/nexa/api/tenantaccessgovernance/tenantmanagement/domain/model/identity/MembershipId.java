package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity;

import java.util.UUID;

public record MembershipId(UUID value) {
	public MembershipId {
		value = UuidIdentitySupport.required(value, "Membership id");
	}

	public MembershipId(String value) {
		this(UuidIdentitySupport.parse(value, "Membership id"));
	}

	public static MembershipId random() {
		return new MembershipId(UUID.randomUUID());
	}

	public static MembershipId from(String value) {
		return new MembershipId(value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
