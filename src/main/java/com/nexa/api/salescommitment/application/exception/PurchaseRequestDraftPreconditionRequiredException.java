package com.nexa.api.salescommitment.application.exception;

public final class PurchaseRequestDraftPreconditionRequiredException extends RuntimeException {
    public PurchaseRequestDraftPreconditionRequiredException() { super("If-Match header is required"); }
}
