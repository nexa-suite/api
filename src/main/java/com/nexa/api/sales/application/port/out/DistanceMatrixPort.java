package com.nexa.api.sales.application.port.out;

import java.util.Optional;

/** Narrow boundary for provider distance and ETA estimates. */
public interface DistanceMatrixPort {
    Optional<DistanceEstimate> estimate(MapCoordinate origin, MapCoordinate destination);

    record DistanceEstimate(long distanceMeters, long durationSeconds, String provider) {
        public DistanceEstimate {
            if (distanceMeters < 0 || durationSeconds < 0) {
                throw new IllegalArgumentException("Distance estimate cannot be negative");
            }
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("Distance provider is required");
            }
        }
    }
}
