package com.nexa.api.inventoryavailability.application.publicapi;

/** Indicates that the Tenant-backed Warehouse capability is not ready for this request. */
public final class WarehouseOperationsStoreUnavailableException extends RuntimeException {
    public WarehouseOperationsStoreUnavailableException() {
        super("Tenant Warehouse operations are unavailable");
    }

    public WarehouseOperationsStoreUnavailableException(Throwable cause) {
        super("Tenant Warehouse operations are unavailable", cause);
    }
}
