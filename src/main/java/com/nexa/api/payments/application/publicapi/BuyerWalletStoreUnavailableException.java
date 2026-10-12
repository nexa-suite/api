package com.nexa.api.payments.application.publicapi;

/** The explicitly selected Tenant-local wallet store is disabled or unavailable. */
public final class BuyerWalletStoreUnavailableException extends RuntimeException {
    public BuyerWalletStoreUnavailableException() {
        super("Tenant wallet storage is unavailable");
    }

    public BuyerWalletStoreUnavailableException(Throwable cause) {
        super("Tenant wallet storage is unavailable", cause);
    }
}
