package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

/** Canonical typed Driver incident facts accepted by MOB-US-005. */
public enum DriverDeliveryIncidentType {
    DELAY,
    INCOMPLETE_INSTRUCTION,
    ACCESS_BLOCKED,
    CUSTOMER_UNAVAILABLE,
    DELIVERY_NOT_EXECUTABLE,
    TEMPERATURE_EXCURSION,
    SAFETY_COMPROMISING_DAMAGE
}
