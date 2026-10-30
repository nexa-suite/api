package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Objects;

public record OrganizationProfile(String legalName, String displayName, String businessIdentifier,
		String operationCategory, long version, String ruc, OrganizationLegalAddress legalAddress) {
	public OrganizationProfile(String legalName, String displayName, String businessIdentifier,
			String operationCategory, long version) {
		this(legalName, displayName, businessIdentifier, operationCategory, version, null, null);
	}

	public OrganizationProfile {
		legalName = required(legalName, "Legal name");
		displayName = required(displayName, "Display name");
		operationCategory = required(operationCategory, "Operation category");
		if (legalName.length() > 160 || displayName.length() > 160 || operationCategory.length() > 80) {
			throw new TenantManagementInvariantViolation("Organization profile exceeds maximum length");
		}
		if (businessIdentifier != null && businessIdentifier.length() > 80) {
			throw new TenantManagementInvariantViolation("Business identifier exceeds maximum length");
		}
		ruc = optional(ruc);
		if (ruc != null && !ruc.matches("[0-9]{11}")) {
			throw new TenantManagementInvariantViolation("RUC must contain exactly 11 digits");
		}
	}

	private static String optional(String value) {
		if (value == null || value.isBlank()) return null;
		return value.strip();
	}

	private static String required(String value, String label) {
		String normalized = Objects.requireNonNullElse(value, "").strip();
		if (normalized.isBlank()) throw new TenantManagementInvariantViolation(label + " is required");
		return normalized;
	}
}
