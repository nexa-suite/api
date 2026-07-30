package com.nexa.api.tenantmanagement.domain.model.tenant;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.text.Normalizer;
import java.util.Locale;

public record TenantSlug(String value) {
	private static final int MAXIMUM_LENGTH = 63;

	public TenantSlug {
		value = normalize(value);
	}

	public static TenantSlug fromName(String value) {
		return new TenantSlug(value);
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Tenant slug is required");
		}
		String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+|-+$", "");
		if (!normalized.matches("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])")) {
			throw new TenantManagementInvariantViolation("Tenant slug must contain 3 to " + MAXIMUM_LENGTH
					+ " lowercase ASCII characters and hyphens");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
