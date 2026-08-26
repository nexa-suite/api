package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

public record DestinationSnapshot(String value) {
    public DestinationSnapshot {
        if (value != null && value.length() > 2000) throw new IllegalArgumentException("Destination snapshot is too long");
        value = value == null ? null : value.trim();
    }
}
