package com.nexa.api.logistics.domain.dispatchorder;

import java.util.UUID;

public record DeliveryIncidentId(UUID value) {
    public DeliveryIncidentId { if (value == null) throw new IllegalArgumentException("Delivery incident id is required"); }
}
