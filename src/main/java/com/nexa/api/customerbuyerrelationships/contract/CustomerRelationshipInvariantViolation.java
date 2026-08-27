package com.nexa.api.customerbuyerrelationships.contract;

public final class CustomerRelationshipInvariantViolation extends RuntimeException {
    public CustomerRelationshipInvariantViolation(String message) {
        super(message);
    }
}
