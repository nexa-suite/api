package com.nexa.api.fulfillmentdelivery.domain.model.fulfillment;

/** Canonical BC-06 fulfillment lifecycle. */
public enum FulfillmentStatus {
    PLANNED,
    ALLOCATED,
    PICKING,
    PICKED,
    PACKED,
    STAGED,
    READY_FOR_DISPATCH,
    HANDED_OVER,
    COMPLETED,
    SHORTAGE,
    HOLD,
    CANCELLED
}
