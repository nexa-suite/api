package com.nexa.api.fulfillmentdelivery.domain.tracking;

import java.time.Duration;
import java.time.Instant;

/** Operational capture and retention are separate constraints. */
public final class DriverLocationPolicy {
    public static final Duration MAX_RETENTION = Duration.ofHours(24);
    private DriverLocationPolicy() { }
    public static boolean valid(double latitude, double longitude, double accuracyMeters,
                                Instant capturedAt, Instant startedAt, Instant now) {
        return Double.isFinite(latitude) && Math.abs(latitude) <= 90
                && Double.isFinite(longitude) && Math.abs(longitude) <= 180
                && Double.isFinite(accuracyMeters) && accuracyMeters >= 0
                && capturedAt != null && startedAt != null && now != null
                && !capturedAt.isBefore(startedAt) && !capturedAt.isAfter(now.plusSeconds(60))
                && capturedAt.isAfter(now.minus(MAX_RETENTION));
    }
}
