package com.nexa.api.payments.application.exception;

/** Raised when a durable payment idempotency key is reused with another intent. */
public final class PaymentIdempotencyPayloadConflictException extends RuntimeException {
    public PaymentIdempotencyPayloadConflictException() {
        super("Idempotency-Key was reused with a different payment reconciliation request");
    }
}
