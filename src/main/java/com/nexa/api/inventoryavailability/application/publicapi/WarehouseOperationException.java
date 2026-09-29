package com.nexa.api.inventoryavailability.application.publicapi;

/** Stable owner error carried across the HTTP problem boundary. */
public class WarehouseOperationException extends RuntimeException {
    private final String code;
    private final boolean notFound;
    public WarehouseOperationException(String code, boolean notFound) {
        super(code); this.code = code; this.notFound = notFound;
    }
    public String code() { return code; }
    public boolean notFound() { return notFound; }
}
