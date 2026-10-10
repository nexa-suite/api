package com.nexa.api.fulfillmentdelivery.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BuyerDeliveryTrackingModels {
    private BuyerDeliveryTrackingModels() { }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items == null ? List.of() : items); }
    }

    /** Internal delivery fact; salesOrderId is used only for owner-contract authorization. */
    public record DeliveryRecord(UUID id, UUID salesOrderId, String salesOrderNumber, String status,
                                 String destination, Instant scheduledAt, Instant dispatchedAt,
                                 Instant deliveredAt, String proofOfDeliveryStatus, long version,
                                 Instant createdAt, Instant updatedAt) { }

    /** Buyer-safe delivery projection. Internal relationship and driver identifiers are omitted. */
    public record DeliveryView(String id, String salesOrderNumber, String status, String destination,
                               Instant scheduledAt, Instant dispatchedAt, Instant deliveredAt,
                               String proofOfDeliveryStatus, long version, Instant createdAt,
                               Instant updatedAt) { }

    /** Buyer-safe timeline fact with no actor, reason, evidence key, or driver assignment fields. */
    public record EventView(String type, Instant occurredAt) { }
}
