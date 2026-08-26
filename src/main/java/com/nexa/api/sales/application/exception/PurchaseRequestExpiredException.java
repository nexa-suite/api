package com.nexa.api.sales.application.exception;

/** Terminal business outcome; expiry state and releases must commit before the 409 response. */
public final class PurchaseRequestExpiredException extends CommercialBusinessException {
    public PurchaseRequestExpiredException() {
        super("PURCHASE_REQUEST_EXPIRED");
    }
}
