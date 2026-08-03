package com.nexa.api.warehouse.domain.model.warehouse;

/** Immutable location value used by Warehouse snapshots. */
public record WarehouseLocation(String address) {
    public WarehouseLocation {
        if (address != null && address.trim().length() > 2000) {
            throw new IllegalArgumentException("Warehouse location is invalid");
        }
        address = address == null ? null : address.trim();
    }
}
