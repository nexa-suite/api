package com.nexa.api.fulfillmentdelivery.domain.load;

import java.time.Instant;
import java.util.UUID;

/** Authenticated Dispatch judgment for compatibility facts with no V1 fleet catalog. */
public record LoadCompatibilityAttestation(boolean capacitySufficient,
                                           boolean handlingCompatible,
                                           boolean zoneReasonable,
                                           boolean noExclusiveTransportRestriction,
                                           UUID attestedByMembershipId,
                                           Instant attestedAt,
                                           String observation) {
    public LoadCompatibilityAttestation {
        if (!capacitySufficient || !handlingCompatible || !zoneReasonable
                || !noExclusiveTransportRestriction || attestedByMembershipId == null || attestedAt == null) {
            throw new IllegalArgumentException("LOAD_COMPATIBILITY_ATTESTATION_REQUIRED");
        }
        observation = observation == null ? null : observation.trim();
        if (observation != null && (observation.isEmpty() || observation.length() > 1000)) {
            throw new IllegalArgumentException("LOAD_COMPATIBILITY_OBSERVATION_INVALID");
        }
    }
}
