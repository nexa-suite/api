package com.nexa.api.fulfillmentdelivery.domain.model.delivery;

/** Canonical BC-06 delivery lifecycle. */
public enum DeliveryStatus {
    PLANNED,
    ASSIGNED,
    DISPATCHED,
    IN_TRANSIT,
    PARTIAL,
    DELIVERED,
    FAILED,
    CANCELLED
}
