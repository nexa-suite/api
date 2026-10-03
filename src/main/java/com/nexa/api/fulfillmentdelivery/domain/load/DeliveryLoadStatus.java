package com.nexa.api.fulfillmentdelivery.domain.load;

/** Durable dispatch and Driver load lifecycle. Responsibility is derived from both attestations. */
public enum DeliveryLoadStatus {
    DRAFT,
    ASSIGNED,
    OFFERED,
    HANDOFF_CONFIRMED,
    DRIVER_ACCEPTED,
    RESPONSIBILITY_TRANSFERRED
}
