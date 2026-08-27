package com.nexa.api.salescommitment.application.exception;

public final class PurchaseRequestDraftConcurrencyException extends RuntimeException {
    public PurchaseRequestDraftConcurrencyException() { super("Purchase request draft version is stale"); }
}
