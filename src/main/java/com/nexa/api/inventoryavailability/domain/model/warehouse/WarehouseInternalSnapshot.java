package com.nexa.api.inventoryavailability.domain.model.warehouse;

import java.util.Objects;
import java.util.UUID;

/** Full operational snapshot for internal Warehouse/Logistics handoffs. */
public record WarehouseInternalSnapshot(UUID warehouseId, String code, String name, String address,
                                        WarehouseStatus status, WarehouseHours hours,
                                        WarehouseServiceability serviceability,
                                        WarehouseSelectionPolicy selectionPolicy,
                                        long warehouseVersion, long settingsVersion) {
    public WarehouseInternalSnapshot {
        Objects.requireNonNull(warehouseId, "Warehouse id is required");
        Objects.requireNonNull(hours, "Warehouse hours are required");
        Objects.requireNonNull(serviceability, "Warehouse serviceability is required");
        Objects.requireNonNull(selectionPolicy, "Warehouse selection policy is required");
        if (warehouseVersion < 0 || settingsVersion < 0) throw new IllegalArgumentException("Snapshot version is invalid");
    }
}
