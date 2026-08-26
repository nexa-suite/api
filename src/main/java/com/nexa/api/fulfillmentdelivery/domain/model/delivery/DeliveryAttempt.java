package com.nexa.api.fulfillmentdelivery.domain.model.delivery;

import java.math.BigDecimal;
import java.util.Objects;

/** Pure validation for one delivery-attempt fact. */
public record DeliveryAttempt(int attemptNumber, DeliveryAttemptOutcome outcome,
                              BigDecimal attemptedQuantity, BigDecimal receivedQuantity,
                              String reason) {
    public DeliveryAttempt {
        if (attemptNumber <= 0 || outcome == null || attemptedQuantity == null || attemptedQuantity.signum() <= 0
                || receivedQuantity == null || receivedQuantity.signum() < 0
                || receivedQuantity.compareTo(attemptedQuantity) > 0) {
            throw new IllegalArgumentException("Delivery attempt quantities are invalid");
        }
        if (outcome == DeliveryAttemptOutcome.FAILED || outcome == DeliveryAttemptOutcome.REFUSED
                || outcome == DeliveryAttemptOutcome.ABSENT) {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Failure reason is required");
        }
        reason = reason == null ? null : reason.trim();
    }

    public boolean isFinal() {
        return outcome == DeliveryAttemptOutcome.DELIVERED
                || outcome == DeliveryAttemptOutcome.PARTIAL
                || outcome == DeliveryAttemptOutcome.REFUSED
                || outcome == DeliveryAttemptOutcome.ABSENT;
    }
}
