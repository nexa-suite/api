package com.nexa.api.logistics.domain.dispatchorder;

public final class DispatchTransitionViolation extends IllegalStateException {
    public DispatchTransitionViolation(String message) { super(message); }
}
