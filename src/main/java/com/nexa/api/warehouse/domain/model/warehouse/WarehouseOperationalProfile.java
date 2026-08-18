package com.nexa.api.warehouse.domain.model.warehouse;

import java.util.Objects;

/** Aggregate projection used to produce internal snapshots and Buyer-safe views. */
public record WarehouseOperationalProfile(WarehouseProfile profile, WarehouseHours hours,
                                          WarehouseServiceability serviceability,
                                          WarehouseSelectionPolicy selectionPolicy,
                                          long settingsVersion) {
    public WarehouseOperationalProfile {
        Objects.requireNonNull(profile, "Warehouse profile is required");
        Objects.requireNonNull(hours, "Warehouse hours are required");
        Objects.requireNonNull(serviceability, "Warehouse serviceability is required");
        Objects.requireNonNull(selectionPolicy, "Warehouse selection policy is required");
        if (settingsVersion < 0) throw new IllegalArgumentException("Settings version is invalid");
    }

    public WarehouseInternalSnapshot internalSnapshot() {
        return new WarehouseInternalSnapshot(profile.id(), profile.code(), profile.name(), profile.location().address(),
                profile.status(), hours, serviceability, selectionPolicy, profile.version(), settingsVersion);
    }

    public WarehouseBuyerProjection buyerProjection() {
        return new WarehouseBuyerProjection(profile.id(), profile.code(), profile.name(), profile.location().address(),
                hours, serviceability.serviceable(), profile.version());
    }
}
