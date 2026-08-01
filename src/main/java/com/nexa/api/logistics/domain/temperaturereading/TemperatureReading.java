package com.nexa.api.logistics.domain.temperaturereading;

import java.math.BigDecimal;
import java.time.Instant;

public record TemperatureReading(BigDecimal value, TemperatureScale scale, Instant recordedAt, String source,
                                 TemperatureReadingStatus status) {
    public TemperatureReading {
        if (value == null || scale == null || recordedAt == null || source == null || source.isBlank() || status == null) throw new IllegalArgumentException("Temperature reading is incomplete");
        source = source.trim();
        if (source.length() > 64) throw new IllegalArgumentException("Temperature source is too long");
        scale.toCelsius(value);
    }
}
