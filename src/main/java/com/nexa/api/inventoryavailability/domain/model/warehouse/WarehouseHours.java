package com.nexa.api.inventoryavailability.domain.model.warehouse;

import java.time.LocalTime;
import java.util.Objects;

/** Operating hours shared by the current workspace operational policy. */
public record WarehouseHours(LocalTime startsAt, LocalTime endsAt) {
    public WarehouseHours {
        Objects.requireNonNull(startsAt, "Warehouse opening time is required");
        Objects.requireNonNull(endsAt, "Warehouse closing time is required");
        if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("Warehouse hours are invalid");
    }
}
