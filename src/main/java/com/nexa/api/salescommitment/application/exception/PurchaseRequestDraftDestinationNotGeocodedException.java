package com.nexa.api.salescommitment.application.exception;

public final class PurchaseRequestDraftDestinationNotGeocodedException extends RuntimeException {
    public PurchaseRequestDraftDestinationNotGeocodedException() {
        super("Delivery destination must have geocoded coordinates before route preview");
    }
}
