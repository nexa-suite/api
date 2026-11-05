package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;
import java.util.Objects;

/** Structured legal address attached to a Tenant organization profile. */
public record OrganizationLegalAddress(String countryCode, String addressLine,
		String departmentCode, String provinceCode, String districtCode, String roadType,
		String streetName, String streetNumber, String interior, String postalCode, String reference) {
	public OrganizationLegalAddress {
		countryCode = required(countryCode, "Country code", 2).toUpperCase(Locale.ROOT);
		if (!countryCode.matches("[A-Z]{2}")) {
			throw new TenantManagementInvariantViolation("Country code must contain two letters");
		}
		addressLine = required(addressLine, "Address line", 240);
		departmentCode = optional(departmentCode, "Department code", 2);
		provinceCode = optional(provinceCode, "Province code", 4);
		districtCode = optional(districtCode, "District code", 6);
		boolean hasAnyGeographyCode = departmentCode != null || provinceCode != null || districtCode != null;
		if (hasAnyGeographyCode && (departmentCode == null || provinceCode == null || districtCode == null)) {
			throw new TenantManagementInvariantViolation("Department, province and district codes must be supplied together");
		}
		if (hasAnyGeographyCode && (!"PE".equals(countryCode)
				|| !departmentCode.matches("[0-9]{2}")
				|| !provinceCode.matches(departmentCode + "[0-9]{2}")
				|| !districtCode.matches(provinceCode + "[0-9]{2}"))) {
			throw new TenantManagementInvariantViolation("Peru geography codes have an invalid structure");
		}
		roadType = optional(roadType, "Road type", 32);
		streetName = optional(streetName, "Street name", 180);
		streetNumber = optional(streetNumber, "Street number", 32);
		interior = optional(interior, "Interior", 64);
		postalCode = optional(postalCode, "Postal code", 32);
		reference = optional(reference, "Address reference", 500);
	}

	private static String required(String value, String label, int maximumLength) {
		String normalized = Objects.requireNonNullElse(value, "").strip();
		if (normalized.isBlank() || normalized.length() > maximumLength) {
			throw new TenantManagementInvariantViolation(label + " is invalid");
		}
		return normalized;
	}

	private static String optional(String value, String label, int maximumLength) {
		if (value == null || value.isBlank()) return null;
		String normalized = value.strip();
		if (normalized.length() > maximumLength) {
			throw new TenantManagementInvariantViolation(label + " exceeds maximum length");
		}
		return normalized;
	}
}
