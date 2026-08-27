package com.nexa.api.inventoryavailability.domain.model.warehouse;

import java.util.Objects;
import java.util.UUID;

/** Warehouse-owned identity and location profile. */
public record WarehouseProfile(UUID id, String code, String name, WarehouseLocation location,
                               WarehouseStatus status, long version) {
    public WarehouseProfile {
        Objects.requireNonNull(id, "Warehouse id is required");
        if (code == null || code.isBlank() || code.trim().length() > 32) throw new IllegalArgumentException("Warehouse code is invalid");
        if (name == null || name.isBlank() || name.trim().length() > 160) throw new IllegalArgumentException("Warehouse name is invalid");
        Objects.requireNonNull(location, "Warehouse location is required");
        Objects.requireNonNull(status, "Warehouse status is required");
        if (version < 0) throw new IllegalArgumentException("Warehouse version is invalid");
        code = code.trim().toUpperCase(java.util.Locale.ROOT);
        name = name.trim();
    }
}
