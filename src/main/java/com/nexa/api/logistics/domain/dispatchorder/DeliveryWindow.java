package com.nexa.api.logistics.domain.dispatchorder;

import java.time.Instant;

public record DeliveryWindow(Instant startsAt, Instant endsAt) {
    public DeliveryWindow {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) throw new IllegalArgumentException("Delivery window must be ordered and non-empty");
    }
}
