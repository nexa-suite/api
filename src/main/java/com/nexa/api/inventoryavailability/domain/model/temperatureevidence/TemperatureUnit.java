package com.nexa.api.inventoryavailability.domain.model.temperatureevidence;

import java.util.Locale;

public enum TemperatureUnit {
    CELSIUS,
    FAHRENHEIT;

    public static TemperatureUnit from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Temperature unit is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported temperature unit", exception);
        }
    }
}
