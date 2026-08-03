package com.nexa.api.sales.application.port.out;

import java.math.BigDecimal;

/** Provider-neutral geographic coordinate. */
public record MapCoordinate(BigDecimal latitude, BigDecimal longitude) {
    public MapCoordinate {
        if (latitude == null || longitude == null
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Map coordinate is invalid");
        }
    }
}
