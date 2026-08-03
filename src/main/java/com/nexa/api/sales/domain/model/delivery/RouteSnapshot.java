package com.nexa.api.sales.domain.model.delivery;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record RouteSnapshot(String provider, String reference, String originLabel, String destinationLabel,
                            long distanceMeters, long durationSeconds, String previewUrl) {
    public RouteSnapshot {
        provider = required(provider, "Route provider", 40);
        reference = required(reference, "Route reference", 160);
        originLabel = required(originLabel, "Route origin", 500);
        destinationLabel = required(destinationLabel, "Route destination", 500);
        if (distanceMeters < 0 || durationSeconds < 0) throw new SalesInvariantViolation("Route measures cannot be negative");
        previewUrl = previewUrl == null || previewUrl.isBlank() ? null : previewUrl.trim();
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
