package com.nexa.api.fulfillmentdelivery.domain.model.delivery;

/** Durable outcome vocabulary for a delivery attempt. */
public enum DeliveryAttemptOutcome {
    PENDING,
    DELIVERED,
    PARTIAL,
    FAILED,
    REFUSED,
    ABSENT
}
