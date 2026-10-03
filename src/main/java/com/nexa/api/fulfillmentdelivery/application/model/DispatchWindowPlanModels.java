package com.nexa.api.fulfillmentdelivery.application.model;

import java.time.Instant;
import java.util.UUID;

/** Explicit Dispatch planning for Fulfillments with no current commercial delivery window. */
public final class DispatchWindowPlanModels {
    private DispatchWindowPlanModels() { }

    public record Request(Instant windowStart, Instant windowEnd, String reason) { }

    public record View(UUID fulfillmentId,
                       long fulfillmentVersion,
                       int revision,
                       Instant windowStart,
                       Instant windowEnd,
                       String reason,
                       UUID recordedByMembershipId,
                       Instant recordedAt,
                       boolean replayed) { }
}
