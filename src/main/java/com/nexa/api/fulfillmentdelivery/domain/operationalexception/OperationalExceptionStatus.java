package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

/** Actor-attributed exception lifecycle; source business authority remains separate. */
public enum OperationalExceptionStatus {
    OPEN,
    CLAIMED,
    UNDER_REVIEW,
    RESOLVED,
    CLOSED
}
