package com.nexa.api.fulfillmentdelivery.domain.temperaturereading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public enum TemperatureScale {
    CELSIUS, FAHRENHEIT;
    public static TemperatureScale from(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Temperature unit is required");
        return switch (value.trim().toUpperCase(Locale.ROOT)) { case "C", "CELSIUS" -> CELSIUS; case "F", "FAHRENHEIT" -> FAHRENHEIT; default -> throw new IllegalArgumentException("Unsupported temperature unit"); };
    }
    public BigDecimal toCelsius(BigDecimal value) {
        if (value == null || !Double.isFinite(value.doubleValue()) || value.compareTo(BigDecimal.valueOf(-1000)) <= 0 || value.compareTo(BigDecimal.valueOf(1000)) >= 0) throw new IllegalArgumentException("Temperature value is out of bounds");
        return this == CELSIUS ? value : value.subtract(BigDecimal.valueOf(32)).multiply(BigDecimal.valueOf(5)).divide(BigDecimal.valueOf(9), 8, RoundingMode.HALF_UP);
    }
}
