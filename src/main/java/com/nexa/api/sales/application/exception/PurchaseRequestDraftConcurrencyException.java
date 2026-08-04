package com.nexa.api.sales.application.exception;

public final class PurchaseRequestDraftConcurrencyException extends RuntimeException {
    public PurchaseRequestDraftConcurrencyException() { super("Purchase request draft version is stale"); }
}
