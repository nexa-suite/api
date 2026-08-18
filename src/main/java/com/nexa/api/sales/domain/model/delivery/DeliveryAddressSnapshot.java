package com.nexa.api.sales.domain.model.delivery;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.address.Address;

import java.util.Objects;

public record DeliveryAddressSnapshot(String id, String label, Address address, boolean defaultAddress) {
    public DeliveryAddressSnapshot {
        id = required(id, "Delivery address id", 64);
        label = required(label, "Delivery address label", 160);
        address = Objects.requireNonNull(address, "Delivery address is required");
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
