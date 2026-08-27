package com.nexa.api.fulfillmentdelivery.domain.handoff;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, append-only note exchanged by Warehouse and Logistics. */
public record OperationalHandoffNote(UUID id, UUID dispatchOrderId, UUID authorMembershipId,
                                     String note, Instant occurredAt, long dispatchVersion) {
    public OperationalHandoffNote {
        Objects.requireNonNull(id, "Handoff note id is required");
        Objects.requireNonNull(dispatchOrderId, "Dispatch order id is required");
        Objects.requireNonNull(authorMembershipId, "Handoff note author is required");
        Objects.requireNonNull(occurredAt, "Handoff note time is required");
        if (note == null || note.isBlank() || note.trim().length() > 2000) {
            throw new IllegalArgumentException("Handoff note is invalid");
        }
        if (dispatchVersion < 0) throw new IllegalArgumentException("Handoff note version is invalid");
        note = note.trim();
    }
}
