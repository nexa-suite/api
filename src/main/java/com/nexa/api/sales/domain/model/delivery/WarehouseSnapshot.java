package com.nexa.api.sales.domain.model.delivery;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.math.BigDecimal;
import java.time.Instant;

    public record WarehouseSnapshot(String id, String code, String name, String address,
                                    String selectionReason, String serviceStatus, int priority,
                                    boolean preferred, Instant selectedAt,
                                    BigDecimal latitude, BigDecimal longitude) {
    public WarehouseSnapshot {
        id = required(id, "Warehouse id", 64);
        code = required(code, "Warehouse code", 32);
        name = required(name, "Warehouse name", 160);
        address = required(address, "Warehouse address", 500);
        selectionReason = selectionReason == null || selectionReason.isBlank() ? "ACTIVE_OPERATIONAL_FALLBACK" : selectionReason.trim();
        serviceStatus = serviceStatus == null || serviceStatus.isBlank() ? "OPERATIONAL" : serviceStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if (priority < 0) throw new SalesInvariantViolation("Warehouse priority cannot be negative");
    }

    public WarehouseSnapshot(String id, String code, String name, String address) {
        this(id, code, name, address, "ACTIVE_OPERATIONAL_FALLBACK", "OPERATIONAL", 0, false,
                Instant.now(), null, null);
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
