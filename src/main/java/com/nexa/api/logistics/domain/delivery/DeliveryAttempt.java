package com.nexa.api.logistics.domain.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable outcome evidence. Failed attempts belong to the same Delivery. */
public record DeliveryAttempt(UUID id, UUID deliveryId, int number, DeliveryAttemptStatus status,
                              String failureReason, Instant occurredAt, List<DeliveryAttemptLine> lines) {
    public DeliveryAttempt {
        if (id == null || deliveryId == null || number < 1 || status == null || occurredAt == null) {
            throw new IllegalArgumentException("Delivery attempt is incomplete");
        }
        if (status == DeliveryAttemptStatus.FAILED && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("Failed delivery attempt requires a reason");
        }
        failureReason = failureReason == null ? null : failureReason.trim();
        if (failureReason != null && failureReason.length() > 2000) {
            throw new IllegalArgumentException("Delivery attempt reason is too long");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (status == DeliveryAttemptStatus.PARTIAL && lines.isEmpty()) {
            throw new IllegalArgumentException("Partial delivery requires delivered lines");
        }
    }
}
