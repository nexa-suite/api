package com.nexa.api.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record UnitPreferences(String massUnit, String temperatureUnit, String distanceUnit, String volumeUnit,
		long version) {
	public UnitPreferences {
		massUnit = normalized(massUnit, Set.of("KG", "LB"), "mass unit");
		temperatureUnit = normalized(temperatureUnit, Set.of("CELSIUS", "FAHRENHEIT"), "temperature unit");
		distanceUnit = normalized(distanceUnit, Set.of("KM", "MI"), "distance unit");
		volumeUnit = normalized(volumeUnit, Set.of("M3", "PALLET", "FT3"), "volume unit");
	}

	private static String normalized(String value, Set<String> allowed, String label) {
		String normalized = Objects.requireNonNullElse(value, "").strip().toUpperCase(Locale.ROOT);
		if (!allowed.contains(normalized)) throw new TenantManagementInvariantViolation("Unsupported " + label);
		return normalized;
	}
}
