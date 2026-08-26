package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

import java.util.UUID;

public record DispatchOrderId(UUID value) {
    public DispatchOrderId { if (value == null) throw new IllegalArgumentException("Dispatch order id is required"); }
}
