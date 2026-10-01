package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

/** Driver-lane states; resolution and closure remain owned by other authority. */
public enum OperationalExceptionStatus {
    OPEN,
    CLAIMED,
    UNDER_REVIEW
}
