package com.nexa.api.logistics.domain;

import java.util.Locale;

/** Units accepted by a temperature reading. */
public enum TemperatureUnit {
	CELSIUS,
	FAHRENHEIT;

	public static TemperatureUnit from(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Temperature unit is required");
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "C", "CELSIUS" -> CELSIUS;
			case "F", "FAHRENHEIT" -> FAHRENHEIT;
			default -> throw new IllegalArgumentException("Unsupported temperature unit");
		};
	}
}
