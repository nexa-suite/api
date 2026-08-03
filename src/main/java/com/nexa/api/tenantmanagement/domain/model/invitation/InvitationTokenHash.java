package com.nexa.api.tenantmanagement.domain.model.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public record InvitationTokenHash(String value) {
	public InvitationTokenHash {
		if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invitation token hash is invalid");
		value = value.toLowerCase(java.util.Locale.ROOT);
	}

	public boolean matches(InvitationTokenHash candidate) {
		return candidate != null && MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII), candidate.value.getBytes(StandardCharsets.US_ASCII));
	}
}
