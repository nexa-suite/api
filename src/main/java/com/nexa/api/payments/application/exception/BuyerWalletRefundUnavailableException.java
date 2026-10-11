package com.nexa.api.payments.application.exception;

/** Wallet refunds are unavailable when the request is not bound to the Tenant wallet session. */
public final class BuyerWalletRefundUnavailableException extends RuntimeException {
    public BuyerWalletRefundUnavailableException() {
        super("Tenant wallet refund capability is unavailable");
    }
}
