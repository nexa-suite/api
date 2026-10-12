package com.nexa.api.payments.application.exception;

/** The requested refund conflicts with the consumed wallet amount or reservation state. */
public final class BuyerWalletRefundConflictException extends RuntimeException {
    public BuyerWalletRefundConflictException() {
        super("Buyer wallet refund conflicts with the consumed reservation");
    }
}
