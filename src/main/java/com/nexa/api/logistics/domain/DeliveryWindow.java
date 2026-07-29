package com.nexa.api.logistics.domain;

import java.time.Instant;

/** A non-empty, ordered interval in which delivery is expected. */
public record DeliveryWindow(Instant startsAt, Instant endsAt) {
	public DeliveryWindow {
		if (startsAt == null || endsAt == null) {
			throw new IllegalArgumentException("Delivery window bounds are required");
		}
		if (!endsAt.isAfter(startsAt)) {
			throw new IllegalArgumentException("Delivery window must end after it starts");
		}
	}
}
