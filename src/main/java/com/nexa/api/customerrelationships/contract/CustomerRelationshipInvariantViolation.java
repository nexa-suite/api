package com.nexa.api.customerrelationships.contract;

public final class CustomerRelationshipInvariantViolation extends RuntimeException {
    public CustomerRelationshipInvariantViolation(String message) {
        super(message);
    }
}
