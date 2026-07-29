package com.nexa.api.logistics.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable temperature observation with an explicit unit and timestamp. */
public record TemperatureReading(BigDecimal value, TemperatureUnit unit, Instant recordedAt) {
	public TemperatureReading {
		if (value == null) {
			throw new IllegalArgumentException("Temperature value is required");
		}
		if (unit == null) {
			throw new IllegalArgumentException("Temperature unit is required");
		}
		if (recordedAt == null) {
			throw new IllegalArgumentException("Temperature recorded-at is required");
		}
	}

	public TemperatureReading(BigDecimal value, String unit, Instant recordedAt) {
		this(value, TemperatureUnit.from(unit), recordedAt);
	}
}
