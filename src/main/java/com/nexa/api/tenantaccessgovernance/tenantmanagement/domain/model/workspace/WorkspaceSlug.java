package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.text.Normalizer;
import java.util.Locale;

public record WorkspaceSlug(String value) {
	private static final int MAXIMUM_LENGTH = 63;

	public WorkspaceSlug {
		value = normalize(value);
	}

	public static WorkspaceSlug fromName(String value) {
		return new WorkspaceSlug(value);
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Workspace slug is required");
		}
		String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+|-+$", "");
		if (!normalized.matches("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])")) {
			throw new TenantManagementInvariantViolation("Workspace slug must contain 3 to " + MAXIMUM_LENGTH
					+ " lowercase ASCII characters and hyphens");
		}
		return normalized;
	}

	@Override
	public String toString() {
		return value;
	}
}
