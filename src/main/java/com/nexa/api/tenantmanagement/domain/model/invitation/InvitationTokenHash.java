package com.nexa.api.tenantmanagement.domain.model.invitation;

public record InvitationTokenHash(String value) {
	public InvitationTokenHash {
		if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invitation token hash is invalid");
		value = value.toLowerCase(java.util.Locale.ROOT);
	}
}
