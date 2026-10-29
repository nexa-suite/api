package com.nexa.api.salescommitment.application.exception;

public final class PurchaseRequestDraftWarehouseSelectionUnavailableException extends RuntimeException {
    public PurchaseRequestDraftWarehouseSelectionUnavailableException() {
        super("No single operational Warehouse can fulfill this Purchase Request draft");
    }
}
