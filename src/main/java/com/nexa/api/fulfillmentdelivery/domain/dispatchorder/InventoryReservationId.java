package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

import java.util.UUID;

public record InventoryReservationId(UUID value) {
    public InventoryReservationId { if (value == null) throw new IllegalArgumentException("Reservation id is required"); }
}
