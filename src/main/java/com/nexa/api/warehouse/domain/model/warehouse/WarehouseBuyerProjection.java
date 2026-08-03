package com.nexa.api.warehouse.domain.model.warehouse;

import java.util.Objects;
import java.util.UUID;

/** Deliberately narrow projection safe for the Buyer surface. */
public record WarehouseBuyerProjection(UUID warehouseId, String code, String name, String address,
                                       WarehouseHours hours, boolean serviceable, long version) {
    public WarehouseBuyerProjection {
        Objects.requireNonNull(warehouseId, "Warehouse id is required");
        Objects.requireNonNull(hours, "Warehouse hours are required");
        if (version < 0) throw new IllegalArgumentException("Warehouse version is invalid");
    }
}
