package com.nexa.api.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Stable local-only identities for repeatable demo/bootstrap environments. */
public final class LocalIdentityIds {
	private static final UUID NAMESPACE = UUID.fromString("8a2f4b36-6a6a-5f2c-9e3c-6d6f4f0f2e11");

	private LocalIdentityIds() {
	}

	public static UUID forTenant(String slug) {
		return stable("tenant", slug);
	}

	public static UUID forWorkspace(UUID tenantId, String slug) {
		return stable("workspace", Objects.requireNonNull(tenantId, "Tenant id is required") + ":" + normalize(slug));
	}

	public static UUID forUser(String email) {
		return stable("user", email);
	}

	public static UUID forMembership(UUID workspaceId, UUID userId) {
		return stable("membership", Objects.requireNonNull(workspaceId, "Workspace id is required") + ":"
				+ Objects.requireNonNull(userId, "User id is required"));
	}

	public static UUID forClientAccount(UUID tenantId, String code) {
		return stable("client-account", Objects.requireNonNull(tenantId, "Tenant id is required") + ":" + normalize(code));
	}

	private static UUID stable(String type, String key) {
		return UUID.nameUUIDFromBytes((type + ":" + normalize(key)).getBytes(StandardCharsets.UTF_8));
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Local identity key is required");
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
