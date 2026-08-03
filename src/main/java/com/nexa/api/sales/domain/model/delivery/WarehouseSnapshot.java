package com.nexa.api.sales.domain.model.delivery;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record WarehouseSnapshot(String id, String code, String name, String address) {
    public WarehouseSnapshot {
        id = required(id, "Warehouse id", 64);
        code = required(code, "Warehouse code", 32);
        name = required(name, "Warehouse name", 160);
        address = required(address, "Warehouse address", 500);
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
