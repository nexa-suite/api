package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Publishes the canonical event after an immutable Buyer receipt is linked to the POD. */
final class DeliveryCompletedOutbox {
    private DeliveryCompletedOutbox() { }

    static void append(CanonicalOutboxPort outbox, UUID tenantId, UUID workspaceId,
                       UUID deliveryId, UUID podId, UUID buyerReceiptId, Instant occurredAt) {
        Objects.requireNonNull(outbox, "Canonical outbox is required").append(
                "DeliveryCompleted.v1", "Delivery", deliveryId, tenantId, workspaceId,
                occurredAt, podId.toString(), null, "1.0", buyerReceiptId.toString(),
                Map.of("deliveryId", deliveryId, "podId", podId));
    }
}
