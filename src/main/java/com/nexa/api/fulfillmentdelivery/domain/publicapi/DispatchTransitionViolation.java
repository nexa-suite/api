package com.nexa.api.fulfillmentdelivery.domain.publicapi;

public final class DispatchTransitionViolation extends IllegalStateException {
    public DispatchTransitionViolation(String message) { super(message); }
}
