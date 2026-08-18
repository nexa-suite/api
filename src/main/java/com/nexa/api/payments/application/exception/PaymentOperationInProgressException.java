package com.nexa.api.payments.application.exception;

/** Technical concurrency guard: one card-provider operation may be active per receivable. */
public final class PaymentOperationInProgressException extends RuntimeException {
    public PaymentOperationInProgressException() {
        super("A card payment operation is already in progress for this receivable");
    }
}
