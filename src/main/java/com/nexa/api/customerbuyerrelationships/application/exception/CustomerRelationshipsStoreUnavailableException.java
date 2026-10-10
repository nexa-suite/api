package com.nexa.api.customerbuyerrelationships.application.exception;

/** Tenant Customer Relationships storage or routing is unavailable; no central fallback is permitted. */
public final class CustomerRelationshipsStoreUnavailableException extends RuntimeException {
    public CustomerRelationshipsStoreUnavailableException(Throwable cause) {
        super("Tenant Customer Relationships are unavailable", cause);
    }
}
