package com.nexa.api.fulfillmentdelivery.application.model;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class BuyerDeliveryTrackingModels {
    private BuyerDeliveryTrackingModels() { }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items == null ? List.of() : items); }
    }

    /** Internal delivery fact; salesOrderId is used only for owner-contract authorization. */
    public record DeliveryRecord(UUID id, UUID salesOrderId, String salesOrderNumber, String salesOrderStatus,
                                 String status,
                                 String destination, Instant scheduledAt, Instant dispatchedAt,
                                 Instant deliveredAt, String proofOfDeliveryStatus, long version,
                                 Instant createdAt, Instant updatedAt) {
        public DeliveryRecord(UUID id, UUID salesOrderId, String salesOrderNumber, String status,
                              String destination, Instant scheduledAt, Instant dispatchedAt,
                              Instant deliveredAt, String proofOfDeliveryStatus, long version,
                              Instant createdAt, Instant updatedAt) {
            this(id, salesOrderId, salesOrderNumber, null, status, destination, scheduledAt, dispatchedAt,
                    deliveredAt, proofOfDeliveryStatus, version, createdAt, updatedAt);
        }
    }

    /** Buyer-safe delivery projection. Internal relationship and driver identifiers are omitted. */
    public record DeliveryView(String id, String salesOrderNumber, String salesOrderStatus,
                               String status, String destination,
                               Instant scheduledAt, Instant dispatchedAt, Instant deliveredAt,
                               String proofOfDeliveryStatus, long version, Instant createdAt,
                               Instant updatedAt) {
        public DeliveryView(String id, String salesOrderNumber, String status, String destination,
                            Instant scheduledAt, Instant dispatchedAt, Instant deliveredAt,
                            String proofOfDeliveryStatus, long version, Instant createdAt,
                            Instant updatedAt) {
            this(id, salesOrderNumber, null, status, destination, scheduledAt, dispatchedAt, deliveredAt,
                    proofOfDeliveryStatus, version, createdAt, updatedAt);
        }
    }

    /** Buyer-safe timeline fact with no actor, reason, evidence key, or driver assignment fields. */
    public record EventView(String type, Instant occurredAt) { }

    /** Buyer-scoped immutable receipt fact; excludes handoff credentials and Driver execution identifiers. */
    public record ReceiptRecord(UUID id, UUID deliveryId, UUID attemptId, UUID customerAccountId,
                                UUID buyerMembershipId, String decision, BigDecimal driverDeliveredQuantity,
                                BigDecimal acceptedQuantity, String reason, Instant occurredAt) { }

    public record ReceiptView(String id, String deliveryId, String attemptId, String decision,
                              BigDecimal driverDeliveredQuantity, BigDecimal acceptedQuantity, String reason,
                              Instant occurredAt, String recordedByMembershipId) { }
}
