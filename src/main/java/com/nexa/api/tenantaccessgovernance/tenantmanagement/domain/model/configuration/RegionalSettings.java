package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record RegionalSettings(String timezone, String language, String currency, String countryRegion,
		String dateTimePolicy, String locale, long version) {
	private static final Set<String> LANGUAGES = Set.of("en", "es");
	private static final Set<String> POLICIES = Set.of("LOCALE", "ISO_8601", "US");

	public RegionalSettings {
		timezone = required(timezone, "Timezone");
		language = required(language, "Language").toLowerCase(Locale.ROOT);
		currency = required(currency, "Currency").toUpperCase(Locale.ROOT);
		countryRegion = required(countryRegion, "Country or region").toUpperCase(Locale.ROOT);
		dateTimePolicy = required(dateTimePolicy, "Date and time policy").toUpperCase(Locale.ROOT);
		locale = required(locale, "Locale");
		if (!LANGUAGES.contains(language)) throw new TenantManagementInvariantViolation("Unsupported language");
		if (!currency.matches("[A-Z]{3}")) throw new TenantManagementInvariantViolation("Currency must be ISO 4217");
		if (!POLICIES.contains(dateTimePolicy)) throw new TenantManagementInvariantViolation("Unsupported date and time policy");
	}

	private static String required(String value, String label) {
		String normalized = Objects.requireNonNullElse(value, "").strip();
		if (normalized.isBlank()) throw new TenantManagementInvariantViolation(label + " is required");
		return normalized;
	}
}
