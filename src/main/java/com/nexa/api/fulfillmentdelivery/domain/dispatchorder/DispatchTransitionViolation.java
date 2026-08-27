package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

public final class DispatchTransitionViolation extends IllegalStateException {
    public DispatchTransitionViolation(String message) { super(message); }
}
