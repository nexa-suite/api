package com.nexa.api.sales.domain.model.delivery;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.time.LocalDate;
import java.util.Objects;

public record DeliverySnapshot(LocalDate requestedDate, String notes, DeliveryAddressSnapshot address,
                               WarehouseSnapshot warehouse, RouteSnapshot route) {
    public DeliverySnapshot {
        requestedDate = Objects.requireNonNull(requestedDate, "Requested delivery date is required");
        if (requestedDate.isBefore(LocalDate.now())) throw new SalesInvariantViolation("Requested delivery date cannot be in the past");
        notes = notes == null || notes.isBlank() ? null : notes.trim();
        if (notes != null && notes.length() > 2000) throw new SalesInvariantViolation("Delivery notes are too long");
        address = Objects.requireNonNull(address, "Delivery address snapshot is required");
        warehouse = Objects.requireNonNull(warehouse, "Warehouse snapshot is required");
        route = Objects.requireNonNull(route, "Route snapshot is required");
    }
}
