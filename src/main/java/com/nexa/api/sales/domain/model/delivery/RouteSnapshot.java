package com.nexa.api.sales.domain.model.delivery;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.math.BigDecimal;
import java.time.Instant;

public record RouteSnapshot(String provider, String reference, String originLabel, String destinationLabel,
                            long distanceMeters, long durationSeconds, String previewUrl,
                            BigDecimal originLatitude, BigDecimal originLongitude,
                            BigDecimal destinationLatitude, BigDecimal destinationLongitude,
                            Instant calculatedAt, String mode, String path) {
    public RouteSnapshot {
        provider = required(provider, "Route provider", 40);
        reference = required(reference, "Route reference", 160);
        originLabel = required(originLabel, "Route origin", 500);
        destinationLabel = required(destinationLabel, "Route destination", 500);
        if (distanceMeters < 0 || durationSeconds < 0) throw new SalesInvariantViolation("Route measures cannot be negative");
        previewUrl = previewUrl == null || previewUrl.isBlank() ? null : previewUrl.trim();
        if ((originLatitude == null) != (originLongitude == null)
                || (destinationLatitude == null) != (destinationLongitude == null)) {
            throw new SalesInvariantViolation("Route coordinates must be supplied in pairs");
        }
        mode = mode == null || mode.isBlank() ? "DRIVING" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        path = path == null || path.isBlank() ? previewUrl : path.trim();
    }

    public RouteSnapshot(String provider, String reference, String originLabel, String destinationLabel,
                         long distanceMeters, long durationSeconds, String previewUrl) {
        this(provider, reference, originLabel, destinationLabel, distanceMeters, durationSeconds, previewUrl,
                null, null, null, null, Instant.now(), "DRIVING", previewUrl);
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
