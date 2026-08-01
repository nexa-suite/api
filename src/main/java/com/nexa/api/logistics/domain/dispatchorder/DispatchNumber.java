package com.nexa.api.logistics.domain.dispatchorder;

import java.util.Objects;

public record DispatchNumber(String value) {
    public DispatchNumber {
        value = Objects.requireNonNull(value, "Dispatch number is required").trim();
        if (!value.matches("DO-[0-9]{4}-[0-9]{6}")) throw new IllegalArgumentException("Invalid dispatch number");
    }
}
